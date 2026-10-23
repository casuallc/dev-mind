package com.devmind.knowledge.sync;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.knowledge.dto.KnowledgeSyncSourceRequest;
import com.devmind.knowledge.dto.KnowledgeSyncSourceView;
import com.devmind.knowledge.dto.SyncSubmitView;
import com.devmind.knowledge.enrich.KnowledgeEnrichService;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeSyncSourceRepository;
import com.devmind.task.api.TaskContext;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.service.AsyncTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-85 FR-27：同步源管理与执行编排。
 * 手动执行提交 CAP-80 任务（重叠策略 SKIP），任务内按 kind 分派 Git/飞书同步；
 * 运行状态与最近统计回写同步源表，前端轮询列表即可看到进度与错误。
 */
@Service
public class KnowledgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncService.class);
    public static final String TASK_TYPE = "knowledge.sync";

    private final KnowledgeSyncSourceRepository repo;
    private final KnowledgeBaseRepository kbRepo;
    private final GitDocSync gitSync;
    private final FeishuSpaceSync feishuSync;
    private final AsyncTaskService taskService;
    private final IdentityService identityService;
    private final KnowledgeEnrichService enrichService;
    private final ObjectMapper mapper;

    public KnowledgeSyncService(KnowledgeSyncSourceRepository repo,
                                KnowledgeBaseRepository kbRepo,
                                GitDocSync gitSync,
                                FeishuSpaceSync feishuSync,
                                @Lazy AsyncTaskService taskService,
                                IdentityService identityService,
                                KnowledgeEnrichService enrichService,
                                ObjectMapper mapper) {
        this.repo = repo;
        this.kbRepo = kbRepo;
        this.gitSync = gitSync;
        this.feishuSync = feishuSync;
        this.taskService = taskService;
        this.identityService = identityService;
        this.enrichService = enrichService;
        this.mapper = mapper;
    }

    public List<KnowledgeSyncSourceView> list(Long kbId) {
        List<KnowledgeSyncSourceEntity> rows = kbId == null
                ? repo.findByOrderByCreatedAtDesc() : repo.findByKbIdOrderByCreatedAtDesc(kbId);
        return rows.stream().map(this::view).toList();
    }

    public KnowledgeSyncSourceView create(KnowledgeSyncSourceRequest req) {
        if (req.kbId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "kbId 必填");
        }
        KnowledgeBaseEntity kb = kbRepo.findById(req.kbId())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "知识库不存在: " + req.kbId()));
        KnowledgeSyncSourceEntity e = new KnowledgeSyncSourceEntity();
        e.setKbId(kb.getId());
        apply(e, req);
        Instant now = Instant.now();
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return view(repo.save(e));
    }

    public KnowledgeSyncSourceView update(Long id, KnowledgeSyncSourceRequest req) {
        KnowledgeSyncSourceEntity e = require(id);
        if (KnowledgeSyncSourceEntity.STATUS_RUNNING.equals(e.getLastStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "同步正在运行，完成后再修改");
        }
        if (req.kbId() != null && !req.kbId().equals(e.getKbId())) {
            kbRepo.findById(req.kbId()).orElseThrow(() -> new DevMindException(
                    ErrorCode.NOT_FOUND, "知识库不存在: " + req.kbId()));
            e.setKbId(req.kbId());
        }
        apply(e, req);
        e.setUpdatedAt(Instant.now());
        return view(repo.save(e));
    }

    public void delete(Long id) {
        KnowledgeSyncSourceEntity e = require(id);
        if (KnowledgeSyncSourceEntity.STATUS_RUNNING.equals(e.getLastStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "同步正在运行，完成后再删除");
        }
        repo.delete(e);
    }

    /** 提交一次同步任务；同一源由任务框架按 SKIP 避免并发。 */
    public SyncSubmitView submit(Long id) {
        KnowledgeSyncSourceEntity e = require(id);
        if (!e.isEnabled()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "同步源已停用，请先启用");
        }
        String taskId = taskService.submit(TASK_TYPE, "{\"sourceId\":" + id + "}",
                TaskOptions.builder()
                        .name("知识同步 " + e.getName())
                        .overlapPolicy("SKIP")
                        .retryMaxAttempts(1)
                        .timeoutMs(2 * 60 * 60_000L)
                        .build());
        return new SyncSubmitView(taskId, id);
    }

    /** 任务执行入口：状态/水位/统计回写。不在此方法加事务（逐条写 + 远程 IO）。 */
    public SyncOutcome execute(Long sourceId, TaskContext ctx) {
        KnowledgeSyncSourceEntity source = repo.findById(sourceId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "同步源不存在: " + sourceId));
        source.setLastStatus(KnowledgeSyncSourceEntity.STATUS_RUNNING);
        source.setLastSyncAt(Instant.now());
        source.setLastError(null);
        source.setUpdatedAt(Instant.now());
        repo.save(source);
        try {
            SyncOutcome outcome = switch (source.getKind()) {
                case KnowledgeSyncSourceEntity.KIND_GIT -> gitSync.run(source, ctx);
                case KnowledgeSyncSourceEntity.KIND_FEISHU -> feishuSync.run(source, ctx);
                default -> throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "不支持的同步源类型: " + source.getKind());
            };
            source.setWatermark(outcome.watermark());
            source.setLastStatsJson(statsJson(outcome));
            source.setLastStatus(KnowledgeSyncSourceEntity.STATUS_OK);
            source.setLastSyncAt(Instant.now());
            source.setUpdatedAt(Instant.now());
            repo.save(source);
            queueEnrich(source, outcome, ctx);
            return outcome;
        } catch (Exception e) {
            source.setLastStatus(KnowledgeSyncSourceEntity.STATUS_FAILED);
            source.setLastError(abbreviate(e.getMessage()));
            source.setLastSyncAt(Instant.now());
            source.setUpdatedAt(Instant.now());
            repo.save(source);
            log.warn("知识同步失败: source={} kind={} err={}", sourceId, source.getKind(), e.toString());
            throw e;
        }
    }

    // ---------------- 内部 ----------------

    /** CAP-85 FR-28：同步有新内容时排队增强（关闭/无变更/失败都不影响同步结果）。 */
    private void queueEnrich(KnowledgeSyncSourceEntity source, SyncOutcome outcome, TaskContext ctx) {
        if (!enrichService.autoEnabled() || outcome.created() + outcome.updated() <= 0) {
            return;
        }
        try {
            String taskId = enrichService.submit(source.getKbId(), null).taskId();
            ctx.log("已排队自动增强任务: " + taskId);
        } catch (Exception e) {
            log.warn("自动增强排队失败（不阻断同步）: source={} err={}", source.getId(), e.toString());
        }
    }

    private void apply(KnowledgeSyncSourceEntity e, KnowledgeSyncSourceRequest req) {
        if (req.kind() != null && !req.kind().isBlank()) {
            String kind = req.kind().strip().toUpperCase(Locale.ROOT);
            if (!KnowledgeSyncSourceEntity.KIND_GIT.equals(kind)
                    && !KnowledgeSyncSourceEntity.KIND_FEISHU.equals(kind)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "kind 必须是 GIT/FEISHU");
            }
            e.setKind(kind);
        }
        if (e.getKind() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "kind 必填");
        }
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().strip());
        }
        if (e.getName() == null || e.getName().isBlank()) {
            e.setName(e.getKind().equals(KnowledgeSyncSourceEntity.KIND_GIT) ? "Git 文档同步" : "飞书目录同步");
        }
        if (req.config() != null && !req.config().isEmpty()) {
            try {
                e.setConfigJson(mapper.writeValueAsString(req.config()));
            } catch (Exception ex) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "同步源配置无法序列化");
            }
        }
        if (e.getConfigJson() == null || e.getConfigJson().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "config 必填");
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
    }

    private KnowledgeSyncSourceEntity require(Long id) {
        return repo.findById(id).orElseThrow(() -> new DevMindException(
                ErrorCode.NOT_FOUND, "同步源不存在: " + id));
    }

    private KnowledgeSyncSourceView view(KnowledgeSyncSourceEntity e) {
        return new KnowledgeSyncSourceView(e.getId(), e.getKbId(), e.getKind(), e.getName(),
                parseConfig(e.getConfigJson()), e.isEnabled(), e.getWatermark(), e.getLastSyncAt(),
                e.getLastStatus(), e.getLastError(), parseStats(e.getLastStatsJson()),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return (Map<String, Object>) mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private KnowledgeSyncSourceView.SyncStats parseStats(String json) {
        if (json == null || json.isBlank()) {
            return KnowledgeSyncSourceView.SyncStats.empty();
        }
        try {
            JsonNode n = mapper.readTree(json);
            return new KnowledgeSyncSourceView.SyncStats(
                    n.path("created").asInt(0), n.path("updated").asInt(0),
                    n.path("unchanged").asInt(0), n.path("deprecated").asInt(0),
                    n.path("failed").asInt(0));
        } catch (Exception e) {
            return KnowledgeSyncSourceView.SyncStats.empty();
        }
    }

    private String statsJson(SyncOutcome o) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "created", o.created(), "updated", o.updated(),
                    "unchanged", o.unchanged(), "deprecated", o.deprecated(),
                    "failed", o.failed()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String abbreviate(String s) {
        String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 900 ? oneLine : oneLine.substring(0, 900) + "…";
    }
}
