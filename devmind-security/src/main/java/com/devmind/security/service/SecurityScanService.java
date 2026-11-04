package com.devmind.security.service;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.notification.dto.ActionDef;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import com.devmind.security.config.SecurityProperties;
import com.devmind.security.engine.NormalizedFinding;
import com.devmind.security.engine.ScanEngineAdapter;
import com.devmind.security.engine.ScanExecContext;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecurityScanEntity;
import com.devmind.security.model.SecurityScanReportEntity;
import com.devmind.security.model.SecurityUploadTokenEntity;
import com.devmind.security.parse.DepTreeParser;
import com.devmind.security.repo.SecurityFindingRepository;
import com.devmind.security.repo.SecurityScanReportRepository;
import com.devmind.security.repo.SecurityScanRepository;
import com.devmind.security.repo.SecurityUploadTokenRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 扫描任务编排（CAP-66 FR-01/03/09）：触发（fail-fast 校验 + 路由 + 基线快照）→
 * 异步逐引擎执行（签上传 token → exec 帧下发 runner → 报告齐后归一化落库）→ 收口通知。
 *
 * <p>红线：触发/异步方法一律不挂 {@code @Transactional}（save 自身事务即时提交，
 * 否则异步线程看不到未提交行）；单引擎失败不拖垮整任务（engine_status 记子状态）；
 * 取消是协作式——引擎步间检查，在跑引擎步跑完当前步即收口。</p>
 */
@Service
public class SecurityScanService {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanService.class);

    /** 报告附件的上传者身份（PRIVATE 可见性=上传者+ADMIN；系统链路无登录态） */
    public static final String REPORT_UPLOADER = "security-scan";

    private static final Set<String> KNOWN_ENGINES = Set.of(
            SecurityFindingEntity.ENGINE_SCA, SecurityFindingEntity.ENGINE_SAST,
            SecurityFindingEntity.ENGINE_SECRETS);

    private final SecurityScanRepository scanRepo;
    private final SecurityFindingRepository findingRepo;
    private final SecurityScanReportRepository reportRepo;
    private final SecurityUploadTokenRepository tokenRepo;
    private final ProjectService projectService;
    private final ProjectRepoRepository projectRepoRepository;
    private final AgentNodeRouter router;
    private final AgentNodeStepRunner stepRunner;
    private final ExecutionLogHub hub;
    private final FindingService findingService;
    private final SecurityProperties props;
    private final ObjectMapper mapper;
    private final ObjectProvider<RepoGitGateway> repoGitGateway;
    private final ObjectProvider<AttachmentContentResolver> attachmentResolver;
    private final ObjectProvider<NotificationService> notificationService;
    private final Map<String, ScanEngineAdapter> adapters = new LinkedHashMap<>();
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(2);
    /** 协作式取消标记（RUNNING 任务在引擎步间检查） */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public SecurityScanService(SecurityScanRepository scanRepo,
                               SecurityFindingRepository findingRepo,
                               SecurityScanReportRepository reportRepo,
                               SecurityUploadTokenRepository tokenRepo,
                               ProjectService projectService,
                               ProjectRepoRepository projectRepoRepository,
                               AgentNodeRouter router,
                               AgentNodeStepRunner stepRunner,
                               ExecutionLogHub hub,
                               FindingService findingService,
                               SecurityProperties props,
                               ObjectMapper mapper,
                               ObjectProvider<RepoGitGateway> repoGitGateway,
                               ObjectProvider<AttachmentContentResolver> attachmentResolver,
                               ObjectProvider<NotificationService> notificationService,
                               List<ScanEngineAdapter> adapterBeans) {
        this.scanRepo = scanRepo;
        this.findingRepo = findingRepo;
        this.reportRepo = reportRepo;
        this.tokenRepo = tokenRepo;
        this.projectService = projectService;
        this.projectRepoRepository = projectRepoRepository;
        this.router = router;
        this.stepRunner = stepRunner;
        this.hub = hub;
        this.findingService = findingService;
        this.props = props;
        this.mapper = mapper;
        this.repoGitGateway = repoGitGateway;
        this.attachmentResolver = attachmentResolver;
        this.notificationService = notificationService;
        for (ScanEngineAdapter a : adapterBeans) {
            adapters.put(a.engineId(), a);
        }
    }

    @PreDestroy
    public void shutdown() {
        scanExecutor.shutdownNow();
    }

    // ---------------- 触发 ----------------

    /** 不用 @Transactional：save 自身事务即时提交，否则异步 run 看不到未提交行。 */
    public SecurityScanEntity create(String projectId, Long repoId, String branch, List<String> engines,
                                     String agentNodeId, String actor) {
        Project project = projectService.requireProject(projectId);
        if (props.getServerUrl() == null || props.getServerUrl().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "平台未配置 devmind.security.server-url（报告上行端点要拼给 runner），请先在配置中补齐");
        }
        if (engines == null || engines.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "至少勾选一个扫描引擎（sca/sast/secrets）");
        }
        for (String e : engines) {
            if (!KNOWN_ENGINES.contains(e)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "未知扫描引擎: " + e);
            }
        }
        ProjectRepoEntity repo = resolveRepo(projectId, repoId);
        if (branch == null || branch.isBlank()) {
            branch = repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()
                    ? repo.getDefaultBranch() : project.baseBranch();
        }
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
        if (scanRepo.existsByProjectIdAndRepoIdAndBranchAndStatusIn(projectId, repo.getId(), branch,
                List.of(SecurityScanEntity.QUEUED, SecurityScanEntity.RUNNING))) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该仓库分支已有扫描在排队/执行中，请等待完成或先取消");
        }
        // CAP-36 路由链：显式 > 项目默认 > 平台默认 > 409（工具链自检走 toolcheck 端点，路由不挂标签门控）
        String nodeId = router.route(agentNodeId, project.agentNodeId(), List.of());
        router.requireExecCapable(nodeId);

        // 基线快照：有服务端克隆的库 fetch + rev-parse（对齐 BuildService 先例）；纯远程库由 runner 按 branch 解析
        String commit = null;
        if (repo.getPath() != null && !repo.getPath().isBlank()) {
            RepoGitGateway gw = repoGitGateway.getIfAvailable();
            if (gw != null) {
                try {
                    gw.fetch(repo.getPath(), branch, actor);
                } catch (Exception e) {
                    log.warn("扫描触发期 fetch 失败（按本地引用继续）: {}", e.toString());
                }
            }
            commit = gitExec(repo.getPath(), "rev-parse", "origin/" + branch);
            if (commit == null || commit.isBlank()) {
                commit = gitExec(repo.getPath(), "rev-parse", "HEAD");
            }
        }

        SecurityScanEntity s = new SecurityScanEntity();
        s.setProjectId(projectId);
        s.setRepoId(repo.getId());
        s.setBranch(branch);
        s.setBaselineCommitSha(commit);
        s.setEngines(String.join(",", engines));
        s.setAgentNodeId(Long.valueOf(nodeId));
        s.setStatus(SecurityScanEntity.QUEUED);
        s.setCreatedBy(actor);
        s.setCreatedAt(Instant.now());
        SecurityScanEntity saved = scanRepo.save(s);
        scanExecutor.submit(() -> run(saved.getId()));
        return saved;
    }

    private ProjectRepoEntity resolveRepo(String projectId, Long repoId) {
        ProjectRepoEntity repo = repoId == null
                ? projectService.primaryRepo(projectId)
                : projectRepoRepository.findById(repoId)
                .filter(r -> projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "仓库不属于项目 " + projectId + ": " + repoId));
        boolean hasLocal = repo.getPath() != null && !repo.getPath().isBlank();
        boolean hasRemote = repo.getRemoteUrl() != null && !repo.getRemoteUrl().isBlank()
                && (repo.getRemoteUrl().startsWith("http://") || repo.getRemoteUrl().startsWith("https://"));
        if (!hasLocal && !hasRemote) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仓库 " + repo.getName() + " 无 remoteUrl 且无服务端克隆，runner 无法拉取代码");
        }
        return repo;
    }

    // ---------------- 查询 ----------------

    public Page<SecurityScanEntity> list(String projectId, int page, int size) {
        return scanRepo.findByProjectIdOrderByIdDesc(projectId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100)));
    }

    public SecurityScanEntity get(long scanId) {
        return scanRepo.findById(scanId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "扫描任务不存在: " + scanId));
    }

    public List<SecurityScanReportEntity> reports(long scanId) {
        return reportRepo.findByScanId(scanId);
    }

    /** 引擎子状态（engine_status JSON 解析；无记录=空 map） */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> engineStatus(SecurityScanEntity scan) {
        if (scan.getEngineStatus() == null || scan.getEngineStatus().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(scan.getEngineStatus(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ---------------- 取消（协作式） ----------------

    public void cancel(long scanId) {
        SecurityScanEntity s = get(scanId);
        if (!SecurityScanEntity.QUEUED.equals(s.getStatus())
                && !SecurityScanEntity.RUNNING.equals(s.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "任务已终态（" + s.getStatus() + "），不能取消");
        }
        cancelled.add(scanId);
        s.setStatus(SecurityScanEntity.CANCELED);
        s.setFinishedAt(Instant.now());
        scanRepo.save(s);
        hub.done(topicOf(scanId), SecurityScanEntity.CANCELED);
    }

    // ---------------- 上传 token ----------------

    /** 签发一次性上传 token（返回明文仅进 exec env；服务端只存 SHA-256） */
    String issueUploadToken(long scanId, String engine) {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        SecurityUploadTokenEntity e = new SecurityUploadTokenEntity();
        e.setScanId(scanId);
        e.setEngine(engine);
        e.setTokenHash(sha256(token));
        e.setExpiresAt(Instant.now().plusSeconds(props.getUploadTokenTtlMinutes() * 60));
        e.setCreatedAt(Instant.now());
        tokenRepo.save(e);
        return token;
    }

    /** 上传校验：哈希命中 + 未过期 + scanId 匹配（engine 匹配由调用方按需比对） */
    public SecurityUploadTokenEntity validateUploadToken(String rawToken, long scanId) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "缺少 X-Scan-Token");
        }
        SecurityUploadTokenEntity e = tokenRepo.findByTokenHash(sha256(rawToken.strip()))
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "上传 token 无效"));
        if (!e.getScanId().equals(scanId)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "上传 token 不属于该扫描任务");
        }
        if (e.getExpiresAt().isBefore(Instant.now())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "上传 token 已过期（引擎执行超过 token 有效期）");
        }
        return e;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- 异步执行 ----------------

    private void run(long scanId) {
        SecurityScanEntity s = scanRepo.findById(scanId).orElse(null);
        if (s == null || !SecurityScanEntity.QUEUED.equals(s.getStatus())) {
            return; // 已取消/不存在
        }
        s.setStatus(SecurityScanEntity.RUNNING);
        s.setStartedAt(Instant.now());
        scanRepo.save(s);

        String topic = topicOf(scanId);
        StringBuilder logs = new StringBuilder();
        Consumer<String> sink = line -> {
            synchronized (logs) {
                logs.append(line).append('\n');
            }
            hub.publishLog(topic, line);
        };
        Map<String, Map<String, Object>> engineStatus = new LinkedHashMap<>();
        int newCount = 0;
        int existingCount = 0;
        int fixedCount = 0;
        int filteredCount = 0;
        int engineOk = 0;
        int engineFailed = 0;
        try {
            ProjectRepoEntity repo = projectRepoRepository.findById(s.getRepoId()).orElse(null);
            AgentExecCommand.Repo execRepo = buildExecRepo(s, repo, sink);
            int stepIndex = 0;
            for (String engineId : s.getEngines().split(",")) {
                if (cancelled.contains(scanId)) {
                    sink.accept("[取消] 任务已被取消，剩余引擎不再执行");
                    break;
                }
                ScanEngineAdapter adapter = adapters.get(engineId.strip());
                if (adapter == null) {
                    sink.accept("[错误] 引擎适配器未装配: " + engineId);
                    engineFailed++;
                    continue;
                }
                EngineOutcome outcome = runEngine(s, adapter, ++stepIndex, execRepo, sink, engineStatus);
                s.setEngineStatus(mapper.writeValueAsString(engineStatus));
                persistLogs(s, logs);
                if (outcome.ok()) {
                    engineOk++;
                    newCount += outcome.result().newCount();
                    existingCount += outcome.result().existingCount();
                    fixedCount += outcome.result().fixedCount();
                    filteredCount += outcome.result().filtered();
                } else {
                    engineFailed++;
                    filteredCount += outcome.filtered();
                }
            }
            if (cancelled.contains(scanId)) {
                return; // cancel() 已置终态
            }
            s.setNewCount(newCount);
            s.setExistingCount(existingCount);
            s.setFixedCount(fixedCount);
            s.setFilteredCount(filteredCount);
            if (engineOk == 0) {
                s.setStatus(SecurityScanEntity.FAILED);
                s.setErrorMessage("全部引擎执行失败（详见引擎子状态）");
            } else {
                // 部分引擎失败 = 任务 SUCCESS（engine_status 记子状态，不拖垮整任务）
                s.setStatus(SecurityScanEntity.SUCCESS);
                if (engineFailed > 0) {
                    s.setErrorMessage(engineFailed + " 个引擎失败（部分成功）");
                }
            }
        } catch (Exception e) {
            log.warn("扫描 {} 编排异常: {}", scanId, e.toString());
            s.setStatus(SecurityScanEntity.FAILED);
            s.setErrorMessage(truncate("编排异常: " + e.getMessage(), 500));
        } finally {
            cancelled.remove(scanId);
            SecurityScanEntity fresh = scanRepo.findById(scanId).orElse(s);
            if (!SecurityScanEntity.CANCELED.equals(fresh.getStatus())) {
                fresh.setNewCount(s.getNewCount());
                fresh.setExistingCount(s.getExistingCount());
                fresh.setFixedCount(s.getFixedCount());
                fresh.setFilteredCount(s.getFilteredCount());
                fresh.setStatus(s.getStatus());
                fresh.setErrorMessage(s.getErrorMessage());
                fresh.setEngineStatus(s.getEngineStatus());
                fresh.setFinishedAt(Instant.now());
            }
            persistLogs(fresh, logs);
            scanRepo.save(fresh);
            hub.done(topic, fresh.getStatus());
            notifyResult(fresh);
        }
    }

    private record EngineOutcome(boolean ok, FindingService.NormalizeResult result, int filtered) {
    }

    /** 单引擎：签 token → 下发 exec → 校验报告回传 → 归一化落库。失败只记引擎子状态不抛出。 */
    private EngineOutcome runEngine(SecurityScanEntity s, ScanEngineAdapter adapter, int stepIndex,
                                    AgentExecCommand.Repo execRepo, Consumer<String> sink,
                                    Map<String, Map<String, Object>> engineStatus) {
        String engine = adapter.engineId();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("displayName", adapter.displayName());
        st.put("status", "RUNNING");
        engineStatus.put(engine, st);
        long start = System.currentTimeMillis();
        sink.accept("[引擎] " + adapter.displayName() + " 开始");
        try {
            String token = issueUploadToken(s.getId(), engine);
            Map<String, String> env = new LinkedHashMap<>();
            env.put(ScanExecContext.ENV_UPLOAD_URL,
                    props.getServerUrl().replaceAll("/+$", "") + "/api/security/scans/" + s.getId() + "/reports");
            env.put(ScanExecContext.ENV_UPLOAD_TOKEN, token);
            env.put(ScanExecContext.ENV_SCAN_ENGINE, engine);
            StepSpec step = new StepSpec("security-" + engine, adapter.execScript(new ScanExecContext(s.getId())),
                    null, "security-scan");
            StepResult r = stepRunner.runStep(String.valueOf(s.getAgentNodeId()), s.getProjectId(),
                    "security-scan-" + s.getId(), stepIndex, step, env, execRepo, null,
                    props.getExecTimeoutSec(), sink);
            if (!r.ok()) {
                st.put("status", "FAILED");
                st.put("error", "引擎执行失败 exit=" + r.exitCode() + ": " + r.error());
                st.put("durationMs", System.currentTimeMillis() - start);
                sink.accept("[引擎] " + engine + " 执行失败: " + r.error());
                return new EngineOutcome(false, null, 0);
            }
            // 报告回传校验：主格式行必须存在（脚本 curl 失败会 exit!=0，双保险查行）
            SecurityScanReportEntity report = reportRepo
                    .findByScanIdAndEngineAndFormat(s.getId(), engine, adapter.reportFormat())
                    .orElse(null);
            if (report == null) {
                st.put("status", "FAILED");
                st.put("error", "引擎执行成功但主报告（" + adapter.reportFormat() + "）未回传");
                st.put("durationMs", System.currentTimeMillis() - start);
                sink.accept("[引擎] " + engine + " 主报告未回传");
                return new EngineOutcome(false, null, 0);
            }
            byte[] reportBytes = resolveAttachment(report.getAttachmentId());
            List<NormalizedFinding> parsed = adapter.parse(reportBytes);
            Map<String, DepTreeParser.DepInfo> depTree = Map.of();
            if (SecurityFindingEntity.ENGINE_SCA.equals(engine)) {
                depTree = loadDepTree(s.getId(), sink);
            }
            FindingService.NormalizeResult result =
                    findingService.persistFindings(s, adapter, parsed, depTree);
            st.put("status", "SUCCESS");
            st.put("raw", result.raw());
            st.put("filtered", result.filtered());
            st.put("persisted", result.persisted());
            st.put("new", result.newCount());
            st.put("existing", result.existingCount());
            st.put("suppressed", result.suppressedCount());
            st.put("fixed", result.fixedCount());
            st.put("durationMs", System.currentTimeMillis() - start);
            sink.accept("[引擎] " + engine + " 完成：原始 " + result.raw() + " 条，白名单过滤 " + result.filtered()
                    + " 条，入库 " + result.persisted() + " 条（NEW " + result.newCount()
                    + " / EXISTING " + result.existingCount() + " / FIXED " + result.fixedCount() + "）");
            return new EngineOutcome(true, result, result.filtered());
        } catch (Exception e) {
            log.warn("扫描 {} 引擎 {} 异常: {}", s.getId(), engine, e.toString());
            st.put("status", "FAILED");
            st.put("error", truncate(e.getMessage(), 400));
            st.put("durationMs", System.currentTimeMillis() - start);
            sink.accept("[引擎] " + engine + " 异常: " + e.getMessage());
            return new EngineOutcome(false, null, 0);
        }
    }

    /** SCA 引入链：deptree 报告行 → Maven 树解析；缺失/解析失败降级空表不阻塞（FR-11-1） */
    private Map<String, DepTreeParser.DepInfo> loadDepTree(long scanId, Consumer<String> sink) {
        SecurityScanReportEntity row = reportRepo
                .findByScanIdAndEngineAndFormat(scanId, SecurityFindingEntity.ENGINE_SCA,
                        SecurityScanReportEntity.FORMAT_DEPTREE)
                .orElse(null);
        if (row == null) {
            sink.accept("[引入链] 依赖树报告未回传（采集失败不阻塞），引入链字段为空");
            return Map.of();
        }
        try {
            byte[] bytes = resolveAttachment(row.getAttachmentId());
            Map<String, DepTreeParser.DepInfo> tree =
                    DepTreeParser.parseMavenTree(new String(bytes, StandardCharsets.UTF_8));
            sink.accept("[引入链] 依赖树解析完成，共 " + tree.size() + " 个构件");
            return tree;
        } catch (Exception e) {
            sink.accept("[引入链] 依赖树解析失败（不阻塞）: " + e.getMessage());
            return Map.of();
        }
    }

    /** 包可见：ExportService 合并 SARIF 复用 */
    byte[] resolveAttachment(String attachmentId) {
        AttachmentContentResolver resolver = attachmentResolver.getIfAvailable();
        if (resolver == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "attachment 模块未装配，无法读取扫描报告");
        }
        return resolver.resolveAny(attachmentId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "报告附件内容读取失败: " + attachmentId))
                .bytes();
    }

    /** AGENT 工作区描述：remoteUrl + 凭据随帧下发（runner 仅内存持有）；纯本地库 null（节点映射目录） */
    private AgentExecCommand.Repo buildExecRepo(SecurityScanEntity s, ProjectRepoEntity repo,
                                                Consumer<String> sink) {
        if (repo == null || repo.getRemoteUrl() == null || repo.getRemoteUrl().isBlank()) {
            sink.accept("[工作区] 仓库无 remoteUrl，runner 使用节点本地映射目录（不回传代码拉取）");
            return null;
        }
        String url = repo.getRemoteUrl().strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sink.accept("[工作区] 非 http(s) 远端，runner 使用节点本地映射目录");
            return null;
        }
        String token = null;
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw != null) {
            token = gw.resolveToken(s.getCreatedBy(), hostOf(url), s.getProjectId()).orElse(null);
        }
        sink.accept(token == null
                ? "[工作区] 未解析到 Git 凭据，按匿名克隆"
                : "[工作区] Git 凭据已随帧下发（runner 仅内存持有）");
        return new AgentExecCommand.Repo(url, s.getBranch(), s.getBaselineCommitSha(), token);
    }

    /** FR-09 任务级通知：NEW 里 HIGH/CRITICAL > 0 → P1；FAILED → P2。 */
    private void notifyResult(SecurityScanEntity s) {
        NotificationService ns = notificationService.getIfAvailable();
        if (ns == null || SecurityScanEntity.CANCELED.equals(s.getStatus())) {
            return;
        }
        try {
            if (SecurityScanEntity.FAILED.equals(s.getStatus())) {
                ns.emit(new NotificationDraft(NotificationLevel.P2, "security.scan.failed",
                        "安全扫描 #" + s.getId() + " 失败",
                        s.getErrorMessage() == null ? "全部引擎执行失败" : s.getErrorMessage(),
                        "SECURITY_SCAN", String.valueOf(s.getId()), s.getProjectId(),
                        List.of(new ActionDef("open", "查看扫描"))));
                return;
            }
            if (SecurityScanEntity.SUCCESS.equals(s.getStatus())) {
                long hot = findingRepo.findByScanIdAndStatus(s.getId(), SecurityFindingEntity.NEW).stream()
                        .filter(f -> NormalizedFinding.HIGH.equals(f.getSeverity())
                                || NormalizedFinding.CRITICAL.equals(f.getSeverity()))
                        .count();
                if (hot > 0) {
                    ns.emit(new NotificationDraft(NotificationLevel.P1, "security.scan.new-findings",
                            "安全扫描 #" + s.getId() + " 发现 " + hot + " 个新高危/严重漏洞",
                            "NEW " + s.getNewCount() + " / EXISTING " + s.getExistingCount()
                                    + " / FIXED " + s.getFixedCount()
                                    + "（其中高危及以上 NEW " + hot + " 条）",
                            "SECURITY_SCAN", String.valueOf(s.getId()), s.getProjectId(),
                            List.of(new ActionDef("open", "立即处理"))));
                }
            }
        } catch (Exception e) {
            log.warn("扫描 {} 通知发送失败（不影响任务结果）: {}", s.getId(), e.toString());
        }
    }

    private void persistLogs(SecurityScanEntity s, StringBuilder logs) {
        synchronized (logs) {
            s.setLogs(logs.toString());
        }
        scanRepo.save(s);
    }

    private static String topicOf(long scanId) {
        return String.valueOf(scanId);
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    /** 服务端克隆库 rev-parse（对齐 BuildService.gitExec；失败返回 null 降级） */
    private static String gitExec(String repoPath, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(repoPath);
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.readLine();
            }
            int code = p.waitFor();
            return code == 0 && out != null ? out.strip() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
