package com.devmind.session.service;

import com.devmind.common.agent.ChatContextLookup;
import com.devmind.common.agent.ChatContextPreparer;
import com.devmind.common.agent.ContextPackageProvider;
import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.exception.DevMindException;
import com.devmind.project.RequirementService;
import com.devmind.project.model.Project;
import com.devmind.project.ProjectService;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-33 上下文包服务（CAP-34 FR-03 通道的供给侧，全仓唯一 {@link ContextPackageProvider}
 * 实现——agent 拉包端点单注入，多 bean 会炸，chat 不得再实现该 SPI）：
 * launch 前经 {@link ContextAssembler} 装配 {@link ContextPackage}（场景绑定 + 项目自动命中
 * + 请求追加三层合并）并缓存，随帧下发 manifest；runner 凭 manifest 经 HTTP 拉包时由
 * {@link #find} 供给。
 *
 * <p>同时实现 {@link ChatContextPreparer}：chat 模块经该 SPI 获得场景预设与装配产物
 * （chat id 不在 sessions 表，find 重建路径经 {@link ChatContextLookup} 反向查 chat_sessions）。</p>
 *
 * <p>缓存 10min 覆盖正常拉取窗口；未命中（重启/TTL 过期后 runner 重试）按 DB 重建——
 * 重建 = 重跑装配（会再次累计 hitCount，视为一次重新注入，与 resume 同语义）。</p>
 */
@Service
public class SessionContextService implements ContextPackageProvider, ChatContextPreparer {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);
    private static final long CACHE_TTL_MS = 10 * 60_000L;

    private final ContextAssembler assembler;
    private final ScenarioService scenarioService;
    private final SessionRepository sessionRepo;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    /** chat 模块装配时可用（find 重建路径识别 chat id） */
    private final ObjectProvider<ChatContextLookup> chatLookupProvider;

    private record Cached(ContextPackage pkg, long at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SessionContextService(ContextAssembler assembler,
                                 ScenarioService scenarioService,
                                 SessionRepository sessionRepo,
                                 ProjectService projectService,
                                 RequirementService requirementService,
                                 ObjectProvider<ChatContextLookup> chatLookupProvider) {
        this.assembler = assembler;
        this.scenarioService = scenarioService;
        this.sessionRepo = sessionRepo;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.chatLookupProvider = chatLookupProvider;
    }

    /** 会话装配产物：manifest 随 launch 帧下发；snapshotJson 落库（FR-07 可追溯）。 */
    public record Prepared(ContextManifest manifest, String snapshotJson) {
    }

    /**
     * 会话 launch 前装配（create/resume 调用）。projectAuto 恒 true：无项目会话仅全局无标签
     * 条目命中（沿用 CAP-04 现状口径）。renderedTaskSpec 须为场景骨架渲染后的任务说明。
     * 空产出返回 null（不带上下文启动）；场景绑定资产失效等 DevMindException 向上传播。
     */
    public Prepared prepare(String sessionId, Project project, SessionScenarioEntity scenario,
                            String renderedTaskSpec, List<String> extraSkillIds,
                            List<Long> extraDocIds, List<String> extraKnowledgeTags) {
        ContextAssemblyRequest req = new ContextAssemblyRequest(
                project != null ? project.id() : null,
                project != null && project.tags() != null ? project.tags() : List.of(),
                scenario != null ? scenarioService.skillIdsOf(scenario) : List.of(),
                extraSkillIds != null ? extraSkillIds : List.of(),
                scenario != null ? scenarioService.docIdsOf(scenario) : List.of(),
                extraDocIds != null ? extraDocIds : List.of(),
                scenario != null ? scenarioService.knowledgeTagsOf(scenario) : List.of(),
                extraKnowledgeTags != null ? extraKnowledgeTags : List.of(),
                true, false);
        ContextAssembler.AssembledContext a = assembleAndCache(sessionId, req,
                scenario != null ? scenario.getCode() : null,
                scenario != null ? scenario.getName() : null,
                scenario != null ? scenario.getExtraContextMd() : null,
                renderedTaskSpec);
        return a != null ? new Prepared(a.manifest(), a.snapshotJson()) : null;
    }

    // ---------------- ChatContextPreparer（CAP-33 FR-05 场景问答） ----------------

    @Override
    public ScenarioPreset preset(String scenarioCode) {
        SessionScenarioEntity s = scenarioService.requireByCode(scenarioCode);
        return new ScenarioPreset(s.getModel(), s.getPermissionMode(), s.getAgentNodeId(),
                ScenarioService.SCOPE_PROJECT.equals(s.getScope()) ? s.getProjectId() : null);
    }

    /**
     * chat 装配：PROJECT 场景「以某项目身份问答」（projectAuto=true，自动命中该项目知识/私有
     * skill）；GLOBAL 场景只带场景绑定资产（projectAuto=false，无项目语义）。
     */
    @Override
    public PreparedContext prepare(String chatId, String scenarioCode, String message) {
        if (scenarioCode == null || scenarioCode.isBlank()) {
            return null;
        }
        SessionScenarioEntity s = scenarioService.requireByCode(scenarioCode);
        String contextProjectId = ScenarioService.SCOPE_PROJECT.equals(s.getScope()) ? s.getProjectId() : null;
        Project project = contextProjectId != null ? projectService.requireProject(contextProjectId) : null;
        String rendered = s.getPromptSkeleton() == null || s.getPromptSkeleton().isBlank()
                ? message : scenarioService.render(s, message, project, null);
        ContextAssemblyRequest req = new ContextAssemblyRequest(
                contextProjectId, project != null && project.tags() != null ? project.tags() : List.of(),
                scenarioService.skillIdsOf(s), List.of(),
                scenarioService.docIdsOf(s), List.of(),
                scenarioService.knowledgeTagsOf(s), List.of(),
                contextProjectId != null, false);
        ContextAssembler.AssembledContext a = assembleAndCache(chatId, req, s.getCode(), s.getName(),
                s.getExtraContextMd(), rendered);
        return a != null
                ? new PreparedContext(a.manifest(), a.snapshotJson(), rendered)
                : new PreparedContext(null, null, rendered);
    }

    // ---------------- ContextPackageProvider（runner 拉包） ----------------

    @Override
    public Optional<ContextPackage> find(String sessionId) {
        Cached c = cache.get(sessionId);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) {
            return Optional.of(c.pkg());
        }
        // 按 DB 重建：先 sessions 表，未命中经 ChatContextLookup 识别 chat（chat id 不在 sessions 表）
        Optional<SessionEntity> oe = sessionRepo.findById(sessionId);
        if (oe.isPresent()) {
            return rebuildForSession(oe.get());
        }
        ChatContextLookup chatLookup = chatLookupProvider.getIfAvailable();
        if (chatLookup == null) {
            return Optional.empty();
        }
        return chatLookup.find(sessionId).flatMap(info -> {
            try {
                prepare(sessionId, info.scenarioCode(), info.initialPrompt()); // 命中即入缓存
                Cached cached = cache.get(sessionId);
                return cached != null ? Optional.of(cached.pkg()) : Optional.empty();
            } catch (Exception e) {
                log.warn("chat 上下文包重建失败: chat={} err={}", sessionId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** sessions 表重建：按落库 scenarioCode 重渲染（best-effort，场景已删则按原文装配）。 */
    private Optional<ContextPackage> rebuildForSession(SessionEntity ent) {
        try {
            Project project = ent.getProjectId() == null || ent.getProjectId().isBlank()
                    ? null : projectService.requireProject(ent.getProjectId());
            SessionScenarioEntity scenario = null;
            if (ent.getScenarioCode() != null && !ent.getScenarioCode().isBlank()) {
                try {
                    scenario = scenarioService.requireByCode(ent.getScenarioCode());
                } catch (DevMindException e) {
                    log.warn("重建时场景已删除，按原始任务装配: session={} scenario={}",
                            ent.getId(), ent.getScenarioCode());
                }
            }
            String rendered = ent.getTaskSpec();
            if (scenario != null) {
                rendered = scenarioService.render(scenario, ent.getTaskSpec(), project,
                        requirementTitle(ent.getRequirementId()));
            }
            prepare(ent.getId(), project, scenario, rendered, null, null, null); // 命中即入缓存
            Cached cached = cache.get(ent.getId());
            return cached != null ? Optional.of(cached.pkg()) : Optional.empty();
        } catch (Exception e) {
            log.warn("上下文包重建失败: session={} err={}", ent.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String requirementTitle(String requirementId) {
        if (requirementId == null || requirementId.isBlank()) {
            return null;
        }
        try {
            return requirementService.requireById(requirementId).getTitle();
        } catch (Exception e) {
            return null; // 需求已删：占位符置空，不阻塞重建
        }
    }

    /** 装配 + 缓存（供 prepare/find 共用）；空产出返回 null 不缓存。 */
    private ContextAssembler.AssembledContext assembleAndCache(String id, ContextAssemblyRequest req,
                                                               String scenarioCode, String scenarioName,
                                                               String extraContextMd, String renderedTaskSpec) {
        ContextAssembler.AssembledContext a = assembler.assemble(req, scenarioCode, scenarioName,
                extraContextMd, renderedTaskSpec);
        if (a != null) {
            cache.put(id, new Cached(a.pkg(), System.currentTimeMillis()));
        }
        return a;
    }
}
