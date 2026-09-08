package com.devmind.session.service;

import com.devmind.common.agent.ContextPackageProvider;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.knowledge.KnowledgeInjector;
import com.devmind.project.model.Project;
import com.devmind.project.ProjectService;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-34 FR-03 会话上下文包服务：launch 前装配 {@link ContextPackage}（本期 = 知识注入块 +
 * settings 白名单；skills/docs 待 CAP-33 填充）并随帧下发 {@link ContextManifest}；
 * runner 凭 manifest 经 HTTP 拉包时由 {@link #find} 供给（实现 common 的
 * {@link ContextPackageProvider} SPI，agent 模块端点消费）。
 *
 * <p>缓存 10min 覆盖正常拉取窗口；未命中（重启/TTL 过期后 runner 重试）按 DB 重建——
 * 重建会再次累计 hitCount，视为一次重新注入（与 resume 重注入同语义）。</p>
 */
@Service
public class SessionContextService implements ContextPackageProvider {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);
    private static final long CACHE_TTL_MS = 10 * 60_000L;

    private final KnowledgeInjector knowledgeInjector;
    private final SessionRepository sessionRepo;
    private final ProjectService projectService;

    private record Cached(ContextPackage pkg, long at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SessionContextService(KnowledgeInjector knowledgeInjector,
                                 SessionRepository sessionRepo,
                                 ProjectService projectService) {
        this.knowledgeInjector = knowledgeInjector;
        this.sessionRepo = sessionRepo;
        this.projectService = projectService;
    }

    /**
     * launch 前装配并缓存，返回随帧下发的 manifest；无命中/未启用 = null（不带上下文启动）。
     * 装配失败由调用方决定降级策略（沿用知识注入失败不阻塞会话的语义）。
     */
    public ContextManifest prepare(String sessionId, Project project, String taskSpec) {
        KnowledgeInjector.InjectionPackage inj = knowledgeInjector.build(project, taskSpec);
        if (inj == null) {
            return null;
        }
        ContextPackage pkg = ContextPackage.of(inj.claudeMd(), inj.settingsLocalJson());
        ContextManifest manifest = ContextPackages.manifestOf(
                ContextPackages.toJsonBytes(pkg), inj.entryCount());
        cache.put(sessionId, new Cached(pkg, System.currentTimeMillis()));
        return manifest;
    }

    @Override
    public Optional<ContextPackage> find(String sessionId) {
        Cached c = cache.get(sessionId);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) {
            return Optional.of(c.pkg());
        }
        Optional<SessionEntity> oe = sessionRepo.findById(sessionId);
        if (oe.isEmpty()) {
            return Optional.empty();
        }
        SessionEntity ent = oe.get();
        Project project;
        try {
            project = ent.getProjectId() == null || ent.getProjectId().isBlank()
                    ? null : projectService.requireProject(ent.getProjectId());
        } catch (Exception e) {
            log.warn("上下文包重建失败（项目不存在）: session={} project={}", sessionId, ent.getProjectId());
            return Optional.empty();
        }
        KnowledgeInjector.InjectionPackage inj = knowledgeInjector.build(project, ent.getTaskSpec());
        return Optional.ofNullable(inj == null ? null : ContextPackage.of(inj.claudeMd(), inj.settingsLocalJson()));
    }
}
