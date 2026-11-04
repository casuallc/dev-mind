package com.devmind.security.service;

import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.dto.SessionView;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.repo.SessionOutputRepository;
import com.devmind.session.repo.SessionScenarioRepository;
import com.devmind.session.service.SessionManagerService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 复核与 AI 修复（CAP-66 FR-08 / FR-11-4）。
 *
 * <p>内置场景 security-review / security-fix 随模块启动幂等 seed 进 session_scenarios
 * （GLOBAL 作用域，全员可用）；发现详情一键起项目会话（CAP-33 场景装配 + CAP-34 runner
 * 执行链路，taskSpec 携带发现全字段 + 修复指导 + 依赖链）。会话产出按约定契约写到
 * {@code .devmind/output/security-review.md} / {@code security-fix.md}，本服务监听
 * session.completed 回写 finding 的 review_note/reviewed_at 与 fix_diff_summary。</p>
 *
 * <p>边界：复核只是记录与建议，不自动改 finding.status；修复不自动合入，收口走既有
 * 会话收口链路（人工确认）。</p>
 */
@Service
public class SecurityAiService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAiService.class);

    public static final String SCENARIO_REVIEW = "security-review";
    public static final String SCENARIO_FIX = "security-fix";
    public static final String OUTPUT_REVIEW = "security-review.md";
    public static final String OUTPUT_FIX = "security-fix.md";

    private static final String REVIEW_SKELETON = """
            你是一名安全漏洞研判专家。项目仓库已在工作区就绪，请阅读真实代码后研判以下安全扫描发现是否成立。

            ## 待研判发现
            {{task}}

            ## 研判要求
            1. 阅读相关源码 / 依赖配置（pom.xml、build.gradle 等），验证该发现是否真实成立。
            2. 给出可利用性分析：攻击向量、触发条件、影响面；若依赖仅在 test/provided 作用域或不可达，明确说明。
            3. 给出处置建议：修复 / 抑制（误报，须附抑制理由草稿）/ 风险接受（须附残余风险说明）。
            4. 将研判结论写入 .devmind/output/security-review.md（Markdown），包含三节：## 结论（真 / 误报 / 无法确定）、## 依据、## 建议。

            注意：本任务是只读研判，不要修改仓库任何文件。
            """;

    private static final String FIX_SKELETON = """
            你是一名安全修复工程师。项目仓库已在工作区就绪，请修复以下安全扫描发现。

            ## 待修复发现
            {{task}}

            ## 修复要求
            1. 按「修复指导」执行：直接依赖升级版本；传递依赖在 dependencyManagement 钉版，或对引入它的顶端直接依赖排包（exclusion）；已停维护组件按替换建议迁移。
            2. 推荐版本旁附有 NVD 版本区间原文供核对；推导不出安全版本时不要编造版本号，改在产出中说明并给出缓解措施。
            3. 修改后运行快速验证（如 mvn -q compile 或项目自带快速构建步），确保改动不破坏构建。
            4. 将修复摘要写入 .devmind/output/security-fix.md：改动文件、目标坐标与版本、验证结果。
            """;

    private final SecurityFindingRepository findingRepo;
    private final SessionScenarioRepository scenarioRepo;
    private final SessionOutputRepository outputRepo;
    private final SessionManagerService sessionManager;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper mapper;
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "security-ai");
        t.setDaemon(true);
        return t;
    });

    public SecurityAiService(SecurityFindingRepository findingRepo,
                             SessionScenarioRepository scenarioRepo,
                             SessionOutputRepository outputRepo,
                             SessionManagerService sessionManager,
                             PlatformTransactionManager txManager,
                             ObjectMapper mapper) {
        this.findingRepo = findingRepo;
        this.scenarioRepo = scenarioRepo;
        this.outputRepo = outputRepo;
        this.sessionManager = sessionManager;
        this.txManager = txManager;
        this.mapper = mapper;
    }

    /** 内置场景 seed（幂等）：已存在即跳过，用户手工改过的不覆盖。 */
    @PostConstruct
    public void seedScenarios() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            seed(SCENARIO_REVIEW, "安全发现 AI 复核", "研判安全扫描发现的真实性并输出复核报告", REVIEW_SKELETON, 900);
            seed(SCENARIO_FIX, "安全发现 AI 修复", "按修复指导修改依赖配置并验证构建", FIX_SKELETON, 901);
        });
    }

    private void seed(String code, String name, String description, String skeleton, int sortOrder) {
        if (scenarioRepo.existsByCode(code)) {
            return;
        }
        SessionScenarioEntity s = new SessionScenarioEntity();
        s.setCode(code);
        s.setName(name);
        s.setDescription(description);
        s.setPromptSkeleton(skeleton);
        s.setScope("GLOBAL");
        s.setEnabled(true);
        s.setSortOrder(sortOrder);
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        scenarioRepo.save(s);
        log.info("安全扫描内置场景已登记: {}", code);
    }

    @PreDestroy
    void shutdown() {
        aiExecutor.shutdown();
    }

    /** FR-08：一键 AI 复核 → 起 security-review 场景会话，返回 sessionId（202 语义，异步出结果）。 */
    public String startReview(Long findingId, String actor) {
        SecurityFindingEntity f = requireFinding(findingId);
        SessionView session = launch(f, SCENARIO_REVIEW, buildTaskSpec(f, false));
        f.setReviewSessionId(session.id());
        findingRepo.save(f);
        return session.id();
    }

    /** FR-11-4：一键 AI 修复 → 起 security-fix 场景会话，返回 sessionId。 */
    public String startFix(Long findingId, String actor) {
        SecurityFindingEntity f = requireFinding(findingId);
        SessionView session = launch(f, SCENARIO_FIX, buildTaskSpec(f, true));
        f.setFixSessionId(session.id());
        findingRepo.save(f);
        return session.id();
    }

    private SecurityFindingEntity requireFinding(Long findingId) {
        return findingRepo.findById(findingId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "安全发现不存在: " + findingId));
    }

    private SessionView launch(SecurityFindingEntity f, String scenarioCode, String taskSpec) {
        List<Long> repoIds = f.getRepoId() != null ? List.of(f.getRepoId()) : null;
        return sessionManager.create(new CreateSessionRequest(
                null, scenarioCode, f.getProjectId(), null, null, taskSpec,
                null, null, null, null, null, repoIds, null, null, null));
    }

    /** taskSpec：发现全字段 + detail（证据/依赖链/修复指导），供 claude 直接研判。 */
    private String buildTaskSpec(SecurityFindingEntity f, boolean forFix) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 引擎: ").append(f.getEngine()).append('\n');
        sb.append("- 严重度: ").append(f.getSeverity()).append('\n');
        sb.append("- 标题: ").append(f.getTitle()).append('\n');
        if (f.getRuleId() != null) {
            sb.append("- 规则/CVE: ").append(f.getRuleId()).append('\n');
        }
        if (f.getFilePath() != null) {
            sb.append("- 位置: ").append(f.getFilePath());
            if (f.getLineNo() != null) {
                sb.append(':').append(f.getLineNo());
            }
            sb.append('\n');
        }
        if (f.getDepScope() != null) {
            sb.append("- 依赖作用域: ").append(f.getDepScope()).append('\n');
        }
        if (f.getDirectDep() != null) {
            sb.append("- 直接依赖: ").append(f.getDirectDep() ? "是" : "否（传递引入）").append('\n');
        }
        Map<String, Object> detail = readDetail(f.getDetailJson());
        appendDetail(sb, "CVE", detail.get("cve"));
        appendDetail(sb, "构件坐标", coords(detail));
        appendDetail(sb, "依赖链", detail.get("depPath"));
        appendDetail(sb, "描述", detail.get("description"));
        if (detail.get("epssScore") != null || Boolean.TRUE.equals(detail.get("kev"))) {
            sb.append("- 威胁情报: EPSS=").append(detail.get("epssScore"))
                    .append(Boolean.TRUE.equals(detail.get("kev")) ? "，在 CISA KEV 目录（已知被利用）" : "")
                    .append('\n');
        }
        Object remediation = detail.get("remediation");
        if (forFix && remediation instanceof Map<?, ?> rem) {
            sb.append("\n## 修复指导\n");
            appendDetail(sb, "建议动作", rem.get("action"));
            appendDetail(sb, "推荐版本", rem.get("recommendedVersion"));
            appendDetail(sb, "NVD 版本区间原文", rem.get("versionRangesRaw"));
            appendDetail(sb, "说明", rem.get("note"));
        }
        return sb.toString();
    }

    private static String coords(Map<String, Object> detail) {
        Object g = detail.get("groupId");
        Object a = detail.get("artifactId");
        Object v = detail.get("depVersion");
        if (g == null && a == null) {
            return null;
        }
        return (g == null ? "" : g + ":") + a + (v == null ? "" : ":" + v);
    }

    private static void appendDetail(StringBuilder sb, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 会话完成回写：离开事件线程异步处理（WS 事件链同步阻塞先例，见 flow 模块同模式）。 */
    @EventListener
    public void onSessionCompleted(SimpleDomainEvent event) {
        if (!"session.completed".equals(event.type()) || event.entityId() == null) {
            return;
        }
        boolean success = Boolean.TRUE.equals(event.success());
        aiExecutor.submit(() -> handleCompleted(event.entityId(), success));
    }

    /** 完成事件处理本体（包可见便于单测同步驱动）。 */
    void handleCompleted(String sessionId, boolean success) {
        try {
            for (SecurityFindingEntity f : findingRepo.findByReviewSessionId(sessionId)) {
                if (success) {
                    outputRepo.findBySessionIdAndFileName(sessionId, OUTPUT_REVIEW).ifPresent(out -> {
                        f.setReviewNote(out.getContent());
                        f.setReviewedAt(Instant.now());
                    });
                }
                // 失败或缺产出：保留 reviewSessionId 供追溯，reviewNote 不覆写
                findingRepo.save(f);
            }
            for (SecurityFindingEntity f : findingRepo.findByFixSessionId(sessionId)) {
                if (success) {
                    outputRepo.findBySessionIdAndFileName(sessionId, OUTPUT_FIX).ifPresent(out -> {
                        f.setFixDiffSummary(truncate(out.getContent(), 500));
                    });
                }
                findingRepo.save(f);
            }
        } catch (Exception e) {
            log.warn("安全发现 AI 产出回写失败(不阻塞): session={} err={}", sessionId, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
