package com.devmind.flow.verify;

import com.devmind.auth.IdentityService;
import com.devmind.build.model.BuildConfigEntity;
import com.devmind.build.service.BuildConfigService;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.common.verify.VerifyTokenService;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyPackCommand;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyPackResult;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyPatchCommand;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyPatchResult;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyPushCommand;
import com.devmind.flow.verify.VerifyNodeGateway.VerifyNodeResult;
import com.devmind.flow.verify.dto.VerifyRunView;
import com.devmind.flow.verify.dto.VerifyTriggerRequest;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.WorktreeManager;
import com.devmind.project.dto.ProjectView;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.BuildStepEntity;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.service.SessionManagerService;
import com.devmind.test.dto.CreateTestRunRequest;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestSuiteRepository;
import com.devmind.test.service.TestRunService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CAP-73 需求开发中验证编排：触发校验链（开关→绑定→可补丁→产物配置→多库→互斥→配额→
 * 无新提交）+ 四阶段链（PUSHING → BUILDING → PATCHING → TESTING）+ 结果回灌会话/通知。
 *
 * <p>节点交互全部走 {@link VerifyNodeGateway}（实现 = devmind-agent WS 帧适配，Phase C
 * 装配）；服务端零执行。互斥 = 应用层按 requirementId 分段锁 + 事务内复查 RUNNING run；
 * 锁随终态自然释放（RUNNING 落终态即放），阶段超时/断连由巡检兜底收口。</p>
 */
@Service
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    /** runner 协议门控（与 AgentProtocol.VERIFY_FRAMES 对齐；经 gateway.supports 判） */
    public static final int VERIFY_PROTOCOL = 22;

    private final VerifyRunRepository runRepo;
    private final VerifyProperties props;
    private final RequirementService requirementService;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final BuildConfigService buildConfigService;
    private final BuildStepRepository buildStepRepo;
    private final SessionRepository sessionRepo;
    private final SessionManagerService sessionManager;
    private final TestRunService testRunService;
    private final TestSuiteRepository testSuiteRepo;
    private final NotificationService notificationService;
    private final IdentityService identityService;
    private final ObjectProvider<RepoGitGateway> gitGatewayProvider;
    private final GitRemoteOps gitRemoteOps;
    private final VerifyTokenService tokenService;
    private final ObjectProvider<VerifyNodeGateway> gatewayProvider;
    private final AgentNodeRouter agentNodeRouter;
    private final VerifyBundleStorage bundleStorage;
    private final WorktreeManager worktreeManager;
    private final ObjectMapper mapper;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 需求级互斥分段锁（check-then-insert 竞态防护；DB RUNNING 行是互斥本体） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public VerifyService(VerifyRunRepository runRepo, VerifyProperties props,
                         RequirementService requirementService, ProjectService projectService,
                         EnvironmentService environmentService, BuildConfigService buildConfigService,
                         BuildStepRepository buildStepRepo, SessionRepository sessionRepo,
                         SessionManagerService sessionManager, TestRunService testRunService,
                         TestSuiteRepository testSuiteRepo, NotificationService notificationService,
                         IdentityService identityService, ObjectProvider<RepoGitGateway> gitGatewayProvider,
                         GitRemoteOps gitRemoteOps, VerifyTokenService tokenService,
                         ObjectProvider<VerifyNodeGateway> gatewayProvider,
                         AgentNodeRouter agentNodeRouter, VerifyBundleStorage bundleStorage,
                         WorktreeManager worktreeManager, ObjectMapper mapper) {
        this.runRepo = runRepo;
        this.props = props;
        this.requirementService = requirementService;
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.buildConfigService = buildConfigService;
        this.buildStepRepo = buildStepRepo;
        this.sessionRepo = sessionRepo;
        this.sessionManager = sessionManager;
        this.testRunService = testRunService;
        this.testSuiteRepo = testSuiteRepo;
        this.notificationService = notificationService;
        this.identityService = identityService;
        this.gitGatewayProvider = gitGatewayProvider;
        this.gitRemoteOps = gitRemoteOps;
        this.tokenService = tokenService;
        this.gatewayProvider = gatewayProvider;
        this.agentNodeRouter = agentNodeRouter;
        this.bundleStorage = bundleStorage;
        this.worktreeManager = worktreeManager;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ---------------- 查询 ----------------

    public List<VerifyRunView> listRuns(String projectId, String requirementId) {
        requirementService.requireEntity(projectId, requirementId);
        return runRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                .map(e -> toView(e).withoutLogs()).toList();
    }

    public VerifyRunView getRun(long id) {
        return toView(requireRun(id));
    }

    /** 最近一次 verify run（收口确认弹窗提示用；无 = null） */
    public VerifyRunView latestRun(String requirementId) {
        List<VerifyRunEntity> all = runRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId);
        return all.isEmpty() ? null : toView(all.get(0)).withoutLogs();
    }

    // ---------------- 触发 ----------------

    /** 人工触发（需求详情页「立即验证」；force 跳过无新提交校验） */
    public VerifyRunView triggerManual(String projectId, String requirementId, boolean force,
                                       VerifyTriggerRequest req) {
        RequirementEntity requirement = requirementService.requireEntity(projectId, requirementId);
        String actor = identityService.currentActor();
        VerifyRunEntity run = doTrigger(VerifyRunEntity.TRIGGER_MANUAL, actor, actor,
                requirement, force, req);
        return toView(run);
    }

    /**
     * agent 通道（devmind-verify 助手脚本 → {@code POST /api/verify/trigger?token=}）：
     * verify 作用域 token 自证 + 触发会话存活检查（会话结束即失效）。人工通道的
     * {@code force} 不对 agent 开放（配额已兜底失败重试）。
     */
    public VerifyRunView triggerAgent(String token, VerifyTriggerRequest req) {
        VerifyTokenService.Payload payload = tokenService.verify(token)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "verify token 无效或已过期（重新打开会话获取新脚本）"));
        SessionEntity session = sessionRepo.findById(payload.sessionId())
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED,
                        "触发会话已不存在（token 随会话失效）"));
        if (!isActive(session)) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED,
                    "触发会话已结束（token 随会话失效），如需验证请走人工入口");
        }
        RequirementEntity requirement = requirementService.requireById(payload.requirementId());
        VerifyRunEntity run = doTrigger(VerifyRunEntity.TRIGGER_AGENT, session.getId(), null,
                requirement, false, req);
        return toView(run);
    }

    /**
     * 触发校验链（按序，先先生效；全程在需求分段锁内，ls-remote 网络调用也在内——
     * 锁粒度是单需求，不阻塞他需求触发）。
     */
    private VerifyRunEntity doTrigger(String triggerType, String triggeredBy, String tokenActor,
                                      RequirementEntity requirement, boolean force,
                                      VerifyTriggerRequest req) {
        String projectId = requirement.getProjectId();
        String requirementId = requirement.getId();
        ProjectView project = projectService.get(projectId);

        // 1. 项目开关
        if (!project.verifyInDevEnabled()) {
            throw new DevMindException(ErrorCode.FORBIDDEN,
                    "项目未开启「需求开发中验证」（项目设置 → verify_in_dev_enabled）");
        }
        // 2. 环境绑定（悬空引用同口径）
        Long envId = requirement.getVerifyEnvironmentId();
        if (envId == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "VERIFY_ENV_UNBOUND: 需求未绑定验证环境（需求编辑 → 验证环境）");
        }
        EnvironmentEntity env;
        try {
            env = environmentService.requireEnvironment(projectId, envId);
        } catch (DevMindException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "VERIFY_ENV_UNBOUND: 绑定的验证环境 " + envId + " 已删除或不属于本项目，请重新绑定");
        }
        // 3. 环境可补丁
        if (isBlank(env.getPatchDir())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "VERIFY_ENV_NOT_PATCHABLE: 环境「" + env.getName() + "」未配置补丁目录"
                            + "（项目环境 → 编辑 → patch_dir）");
        }
        // 4. 构建产物配置（v1 仅支持 AGENT 构建执行器）
        BuildConfigEntity buildCfg = buildConfigService.entityOrNull(projectId);
        if (buildCfg == null || isBlank(buildCfg.getOutputDir())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "VERIFY_BUILD_NO_OUTPUT: 构建配置未设置产物目录（构建配置 → output_dir，"
                            + "须与环境 patch_dir 同构的完整部署物树）");
        }
        if (!"AGENT".equalsIgnoreCase(buildCfg.getExecutor())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "验证链构建需 executor=AGENT 的构建节点（LOCAL 服务端本地构建本期不支持）");
        }
        List<BuildStepEntity> steps = buildStepRepo.findByProjectIdOrderBySortOrderAsc(projectId);
        if (steps.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目未配置构建步骤（build_steps）");
        }
        // 5. 多库限制
        List<RepoView> codeRepos = projectService.listRepos(projectId).stream()
                .filter(r -> r.role() == null || ProjectRepoEntity.ROLE_CODE.equals(r.role()))
                .toList();
        if (codeRepos.size() > 1) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "VERIFY_MULTI_REPO_UNSUPPORTED: 多库聚合项目暂不支持开发中验证（另立 CAP）");
        }
        RepoView repo = codeRepos.stream().filter(RepoView::primary).findFirst()
                .orElse(codeRepos.isEmpty() ? null : codeRepos.get(0));
        if (repo == null || isBlank(repo.remoteUrl())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "项目主库未配置 remote_url，无法推送/探测需求分支");
        }
        // 节点预检（触发期失败快败，不留后台 FAILED 记录）
        VerifyNodeGateway gateway = requireGateway();
        String buildNodeId = agentNodeRouter.route(
                blankToNull(buildCfg.getAgentNodeId()), project.agentNodeId(), null);
        requireVerifyCapable(gateway, buildNodeId, "构建节点");
        List<String> envNodes = environmentService.nodeIdsOf(env);
        if (envNodes.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "环境「" + env.getName() + "」未配置节点（补丁下发需环境节点在线）");
        }
        String envNodeId = envNodes.get(0);
        requireVerifyCapable(gateway, envNodeId, "环境节点");
        SessionEntity wsSession = latestSessionWithWorkspace(requirementId);

        String branch = worktreeManager.branchFor(requirementId, null);
        Object lock = locks.computeIfAbsent(requirementId, k -> new Object());
        try {
            synchronized (lock) {
                // 6. 需求互斥
                if (runRepo.existsByRequirementIdAndStatus(requirementId,
                        VerifyRunEntity.STATUS_RUNNING)) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "该需求已有进行中的验证 run，等其结束后再触发");
                }
                // 7. 日配额（受理即计，含失败）
                int quota = project.verifyDailyQuota();
                if (quota > 0) {
                    Instant dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
                    long used = runRepo.countByProjectIdAndCreatedAtGreaterThanEqual(projectId, dayStart);
                    if (used >= quota) {
                        throw new DevMindException(ErrorCode.TOO_MANY_SESSIONS,
                                "项目验证日配额已用尽（" + used + "/" + quota
                                        + "），次日重置；临时调大走项目设置 → verify_daily_quota");
                    }
                }
                // 8. 远端 HEAD 探测 + 无新提交判定
                String repoToken = resolveRepoToken(tokenActor, repo.remoteUrl(), projectId);
                GitRemoteOps.GitResult ls = gitRemoteOps.lsRemote(
                        repo.remoteUrl(), repoToken, "refs/heads/" + branch);
                if (!ls.ok()) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "VERIFY_REPO_UNREACHABLE: 仓库不可达（" + abbrev(ls.output())
                                    + "），检查集成凭据与网络");
                }
                String remoteHead = parseSha(ls.output());
                if (remoteHead == null && wsSession == null) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "VERIFY_NO_BRANCH: 远端无分支 " + branch + " 且本需求无会话工作区"
                                    + "（先在该需求下起会话开发并提交）");
                }
                if (remoteHead != null && !force) {
                    Optional<VerifyRunEntity> lastOk = runRepo
                            .findFirstByRequirementIdAndStatusOrderByCreatedAtDesc(
                                    requirementId, VerifyRunEntity.STATUS_SUCCESS);
                    if (lastOk.isPresent() && remoteHead.equals(lastOk.get().getCommitSha())) {
                        throw new DevMindException(ErrorCode.CONFLICT,
                                "VERIFY_NO_NEW_COMMIT: 远端 HEAD 与最近一次验证成功相同（"
                                        + remoteHead.substring(0, Math.min(8, remoteHead.length()))
                                        + "），请先提交新改动；修复环境后重试用人工通道 force=true");
                    }
                }
                // 会话节点离线口径（有工作区可能压着未推送提交，禁静默验旧代码）
                if (wsSession != null && !gateway.isOnline(wsSession.getAgentNodeId())) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "VERIFY_NODE_OFFLINE: 会话节点 " + wsSession.getAgentNodeId()
                                    + " 离线，工作区可能有未推送提交；请先上线节点再触发");
                }
                // 受理落库（RUNNING 即互斥本体）
                VerifyRunEntity run = new VerifyRunEntity();
                run.setRequirementId(requirementId);
                run.setProjectId(projectId);
                run.setTriggerType(triggerType);
                run.setTriggeredBy(triggeredBy);
                run.setEnvironmentId(envId);
                run.setCommitSha(remoteHead);
                run.setStatus(VerifyRunEntity.STATUS_RUNNING);
                run.setModulesHint(req != null ? blankToNull(req.modules()) : null);
                run.setNote(req != null ? blankToNull(req.note()) : null);
                run.setBuildNodeId(buildNodeId);
                run.setEnvNodeId(envNodeId);
                run.setCreatedAt(Instant.now());
                VerifyRunEntity saved = runRepo.save(run);
                executor.submit(() -> orchestrate(saved.getId()));
                return saved;
            }
        } finally {
            locks.remove(requirementId, lock);
        }
    }

    // ---------------- 编排（虚拟线程，禁 @Transactional） ----------------

    /** 四阶段链本体；任何失败收口为 FAILED + 失败阶段，结果回灌/通知在 finally。 */
    void orchestrate(Long runId) {
        VerifyRunEntity run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        long startMs = System.currentTimeMillis();
        Map<String, Long> timings = new LinkedHashMap<>();
        LogBuffer logs = new LogBuffer();
        String failStage = null;
        String failSummary = null;
        try {
            VerifyNodeGateway gateway = requireGateway();
            RequirementEntity requirement = requirementService.requireById(run.getRequirementId());
            EnvironmentEntity env = environmentService.requireEnvironment(
                    run.getProjectId(), run.getEnvironmentId());
            BuildConfigEntity buildCfg = buildConfigService.entityOrNull(run.getProjectId());
            List<BuildStepEntity> steps = buildStepRepo
                    .findByProjectIdOrderBySortOrderAsc(run.getProjectId());
            RepoView repo = primaryCodeRepo(run.getProjectId());
            String branch = worktreeManager.branchFor(run.getRequirementId(), null);
            String tokenActor = VerifyRunEntity.TRIGGER_MANUAL.equals(run.getTriggerType())
                    ? run.getTriggeredBy() : null;
            String repoToken = resolveRepoToken(tokenActor, repo.remoteUrl(), run.getProjectId());

            // ---- PUSHING ----
            SessionEntity wsSession = latestSessionWithWorkspace(run.getRequirementId());
            if (wsSession != null) {
                run.setStage(VerifyRunEntity.STAGE_PUSHING);
                runRepo.save(run);
                long t0 = System.currentTimeMillis();
                logs.accept("[PUSHING] 会话节点 " + wsSession.getAgentNodeId() + " 推送 " + branch);
                VerifyNodeResult push = gateway.push(wsSession.getAgentNodeId(),
                        new VerifyPushCommand(run.getProjectId(), wsSession.getWorkspaceOwner(),
                                worktreeManager.workspaceKeyFor(run.getRequirementId(), null),
                                repo.remoteUrl(), branch, repoToken, props.getPushTimeoutSec()),
                        logs);
                timings.put(VerifyRunEntity.STAGE_PUSHING, System.currentTimeMillis() - t0);
                if (!push.ok()) {
                    failStage = VerifyRunEntity.STAGE_PUSHING;
                    failSummary = "push 失败: " + push.error();
                    return;
                }
                run.setPushStatus(VerifyRunEntity.PUSH_PUSHED);
                // push 后实测远端 HEAD 落 commit_sha（触发时无分支的情形在此补齐）
                GitRemoteOps.GitResult ls = gitRemoteOps.lsRemote(
                        repo.remoteUrl(), repoToken, "refs/heads/" + branch);
                if (ls.ok()) {
                    String head = parseSha(ls.output());
                    if (head != null) {
                        run.setCommitSha(head);
                    }
                }
                runRepo.save(run);
            } else {
                run.setPushStatus(VerifyRunEntity.PUSH_SKIPPED);
                logs.accept("[PUSHING] 无会话工作区，跳过 push（验证基准 = 远端 HEAD）");
                runRepo.save(run);
            }

            // ---- BUILDING ----
            run.setStage(VerifyRunEntity.STAGE_BUILDING);
            runRepo.save(run);
            long t1 = System.currentTimeMillis();
            List<VerifyNodeGateway.BuildStep> stepSpecs = new ArrayList<>();
            for (BuildStepEntity s : steps) {
                stepSpecs.add(new VerifyNodeGateway.BuildStep(s.getName(), s.getCommand(),
                        s.getWorkingDir()));
            }
            Map<String, String> buildEnv = new LinkedHashMap<>();
            if (!isBlank(run.getModulesHint())) {
                buildEnv.put("VERIFY_MODULES", run.getModulesHint());
            }
            logs.accept("[BUILDING] 构建节点 " + run.getBuildNodeId() + " 构建 verify 工作区（commit="
                    + abbrev(run.getCommitSha()) + "）");
            VerifyPackResult pack = gateway.pack(run.getBuildNodeId(),
                    new VerifyPackCommand(run.getProjectId(), "verify-req-" + run.getRequirementId(),
                            repo.remoteUrl(), branch, run.getCommitSha(), repoToken,
                            stepSpecs, buildCfg.getOutputDir(), buildEnv,
                            run.getId(), props.getBuildTimeoutSec(),
                            props.getBundleMaxMb() * 1024L * 1024L),
                    logs);
            timings.put(VerifyRunEntity.STAGE_BUILDING, System.currentTimeMillis() - t1);
            if (!pack.ok()) {
                failStage = VerifyRunEntity.STAGE_BUILDING;
                failSummary = pack.error();
                return;
            }
            logs.accept("[BUILDING] 产物树 " + pack.fileCount() + " 个文件已打包上传");

            // ---- PATCHING ----
            run.setStage(VerifyRunEntity.STAGE_PATCHING);
            runRepo.save(run);
            long t2 = System.currentTimeMillis();
            String manifestKey = env.getId() + "-req" + run.getRequirementId();
            logs.accept("[PATCHING] 环境节点 " + run.getEnvNodeId() + " 补丁下发到 " + env.getPatchDir());
            VerifyPatchResult patch = gateway.patch(run.getEnvNodeId(),
                    new VerifyPatchCommand(run.getId(), env.getPatchDir(),
                            env.getPatchPreHook(), env.getPatchPostHook(),
                            manifestKey, props.getPatchTimeoutSec()),
                    logs);
            timings.put(VerifyRunEntity.STAGE_PATCHING, System.currentTimeMillis() - t2);
            if (!patch.ok()) {
                failStage = VerifyRunEntity.STAGE_PATCHING;
                failSummary = patch.error();
                return;
            }
            run.setFilesChanged(patch.filesChanged());
            runRepo.save(run);
            logs.accept("[PATCHING] 补丁下发完成，变更文件 " + patch.filesChanged() + " 个");

            // ---- TESTING ----
            run.setStage(VerifyRunEntity.STAGE_TESTING);
            runRepo.save(run);
            long t3 = System.currentTimeMillis();
            List<TestSuiteEntity> smoke = testSuiteRepo.findByProjectIdAndKindOrderByCreatedAtDesc(
                    run.getProjectId(), "smoke");
            if (smoke.isEmpty()) {
                logs.accept("[TESTING] 项目无冒烟套件，跳过冒烟验证");
            } else {
                TestSuiteEntity suite = smoke.get(0);
                logs.accept("[TESTING] 冒烟套件 " + suite.getId() + " 运行中");
                TestRunView tv = testRunService.createAs(new CreateTestRunRequest(
                        run.getProjectId(), null, List.of(suite.getId()), null, null,
                        run.getEnvironmentId(), null), "verify-run-" + run.getId());
                run.setTestRunId(tv.id());
                runRepo.save(run);
                TestRunView terminal = awaitTestRun(tv.id(), props.getTestTimeoutSec());
                timings.put(VerifyRunEntity.STAGE_TESTING, System.currentTimeMillis() - t3);
                if (terminal == null) {
                    failStage = VerifyRunEntity.STAGE_TESTING;
                    failSummary = "冒烟运行超时（>" + props.getTestTimeoutSec() + "s），testRun=" + tv.id();
                    return;
                }
                if (!TestRunEntity.SUCCESS.equals(terminal.status())) {
                    failStage = VerifyRunEntity.STAGE_TESTING;
                    failSummary = "冒烟失败（testRun=" + tv.id() + "）: "
                            + (terminal.errorSummary() != null ? terminal.errorSummary()
                                    : "见测试运行详情");
                    return;
                }
                logs.accept("[TESTING] 冒烟通过");
            }

            run.setStatus(VerifyRunEntity.STATUS_SUCCESS);
            run.setStage(null);
            log.info("CAP-73 验证成功: run={} req={} commit={}", run.getId(),
                    run.getRequirementId(), abbrev(run.getCommitSha()));
        } catch (Exception e) {
            log.warn("CAP-73 验证编排异常: run={} err={}", runId, e.toString());
            run.setStatus(VerifyRunEntity.STATUS_FAILED);
            if (failStage == null) {
                failStage = run.getStage();
            }
            failSummary = "编排异常: " + e.getMessage();
        } finally {
            if (failStage != null) {
                run.setStatus(VerifyRunEntity.STATUS_FAILED);
                run.setStage(failStage);
                run.setFailureSummary(truncate(failSummary + "\n--- 日志尾部 ---\n" + logs.tail(4000), 8000));
            }
            run.setDurationMs(System.currentTimeMillis() - startMs);
            run.setStageTimingsJson(writeTimings(timings));
            run.setLogsText(logs.text());
            run.setFinishedAt(Instant.now());
            try {
                runRepo.save(run);
            } catch (Exception e) {
                log.warn("CAP-73 run 终态落库失败: run={} err={}", runId, e.getMessage());
            }
            bundleStorage.cleanupOnTerminal(run);
            deliverResult(run);
        }
    }

    /** 轮询冒烟运行到终态（超时返回 null；事件耦合留给后续版本） */
    private TestRunView awaitTestRun(Long testRunId, long timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        while (System.currentTimeMillis() < deadline) {
            TestRunView v = testRunService.get(testRunId);
            if (!TestRunEntity.QUEUED.equals(v.status()) && !TestRunEntity.RUNNING.equals(v.status())) {
                return v;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ---------------- 结果回灌（FR-06） ----------------

    /** 终态投递：AGENT → 触发会话；MANUAL → 该需求全部进行中会话；无会话 → 通知。 */
    private void deliverResult(VerifyRunEntity run) {
        String message = buildResultMessage(run);
        List<String> targets = new ArrayList<>();
        if (VerifyRunEntity.TRIGGER_AGENT.equals(run.getTriggerType()) && run.getTriggeredBy() != null) {
            targets.add(run.getTriggeredBy());
        } else {
            for (SessionEntity s : sessionRepo.findByRequirementIdOrderByCreatedAtDesc(
                    run.getRequirementId())) {
                if (isActive(s)) {
                    targets.add(s.getId());
                }
            }
        }
        boolean injected = false;
        for (String sessionId : targets) {
            try {
                sessionManager.input(sessionId, message);
                injected = true;
            } catch (Exception e) {
                log.info("CAP-73 结果回灌会话失败（降级通知）: session={} err={}", sessionId, e.getMessage());
            }
        }
        if (!injected) {
            boolean ok = VerifyRunEntity.STATUS_SUCCESS.equals(run.getStatus());
            notificationService.emit(new NotificationDraft(
                    ok ? NotificationLevel.P1 : NotificationLevel.P0,
                    "verify.run.finished",
                    (ok ? "验证通过" : "验证失败") + " · 需求 " + run.getRequirementId(),
                    message, "requirement", run.getRequirementId(), run.getProjectId(), List.of()));
        }
    }

    private String buildResultMessage(VerifyRunEntity run) {
        StringBuilder sb = new StringBuilder();
        boolean ok = VerifyRunEntity.STATUS_SUCCESS.equals(run.getStatus());
        sb.append(ok ? "【验证通过】devmind-verify 全链完成" : "【验证失败】devmind-verify 在 ")
                .append(ok ? "" : run.getStage() + " 阶段失败");
        if (run.getCommitSha() != null) {
            sb.append("；commit=").append(abbrev(run.getCommitSha()));
        }
        if (run.getFilesChanged() != null) {
            sb.append("；变更文件 ").append(run.getFilesChanged()).append(" 个");
        }
        if (VerifyRunEntity.PUSH_SKIPPED.equals(run.getPushStatus())) {
            sb.append("；注意：本次验的是远端 HEAD，本地未推送改动不在其中");
        }
        if (ok) {
            String baseUrl = baseUrlOf(run);
            if (baseUrl != null) {
                sb.append("；访问地址 ").append(baseUrl);
            }
        } else if (run.getFailureSummary() != null) {
            sb.append("\n").append(truncate(run.getFailureSummary(), 2000));
        }
        return sb.toString();
    }

    private String baseUrlOf(VerifyRunEntity run) {
        try {
            EnvironmentEntity env = environmentService.requireEnvironment(
                    run.getProjectId(), run.getEnvironmentId());
            Map<String, String> vars = environmentService.variablesOf(env);
            String url = vars.getOrDefault("baseUrl", vars.get("BASE_URL"));
            return isBlank(url) ? null : url.strip();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 僵尸 run 巡检 ----------------

    /** 节点断连/服务端重启留下的 RUNNING 僵尸：超全链超时上限即判 FAILED 释放互斥。 */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void sweepStaleRuns() {
        long maxMs = (props.getPushTimeoutSec() + props.getBuildTimeoutSec()
                + props.getPatchTimeoutSec() + props.getTestTimeoutSec() + 600) * 1000;
        Instant cutoff = Instant.now().minusMillis(maxMs);
        for (VerifyRunEntity run : runRepo.findByStatusAndCreatedAtBefore(
                VerifyRunEntity.STATUS_RUNNING, cutoff)) {
            run.setStatus(VerifyRunEntity.STATUS_FAILED);
            run.setFailureSummary("编排中断（节点断连或服务端重启），run 超全链超时上限被巡检收口");
            run.setFinishedAt(Instant.now());
            runRepo.save(run);
            log.warn("CAP-73 僵尸 run 已收口: run={} req={}", run.getId(), run.getRequirementId());
        }
    }

    // ---------------- 内部工具 ----------------

    private VerifyNodeGateway requireGateway() {
        VerifyNodeGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "VERIFY_RUNNER_UPGRADE_REQUIRED: 验证链节点通道未装配（需 devmind-agent 模块）");
        }
        return gateway;
    }

    private void requireVerifyCapable(VerifyNodeGateway gateway, String nodeId, String role) {
        if (!gateway.isOnline(nodeId)) {
            throw new DevMindException(ErrorCode.CONFLICT, role + " " + nodeId + " 不在线");
        }
        if (!gateway.supports(nodeId, VERIFY_PROTOCOL)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "VERIFY_RUNNER_UPGRADE_REQUIRED: " + role + " " + nodeId
                            + " 的 runner 协议版本过低（验证链需 v" + VERIFY_PROTOCOL
                            + "+），请到节点页升级 runner");
        }
    }

    /** 该需求最近一棵会话工作区所属会话（有 agentNodeId 即工作区曾物化在 runner 上） */
    private SessionEntity latestSessionWithWorkspace(String requirementId) {
        for (SessionEntity s : sessionRepo.findByRequirementIdOrderByCreatedAtDesc(requirementId)) {
            if (!isBlank(s.getAgentNodeId())) {
                return s;
            }
        }
        return null;
    }

    private RepoView primaryCodeRepo(String projectId) {
        List<RepoView> codeRepos = projectService.listRepos(projectId).stream()
                .filter(r -> r.role() == null || ProjectRepoEntity.ROLE_CODE.equals(r.role()))
                .toList();
        RepoView repo = codeRepos.stream().filter(RepoView::primary).findFirst()
                .orElse(codeRepos.isEmpty() ? null : codeRepos.get(0));
        if (repo == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目无代码仓库");
        }
        return repo;
    }

    private String resolveRepoToken(String actor, String remoteUrl, String projectId) {
        RepoGitGateway gateway = gitGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return null;
        }
        String host = null;
        try {
            host = URI.create(remoteUrl.trim()).getHost();
        } catch (Exception ignored) {
        }
        return gateway.resolveToken(actor, host, projectId).orElse(null);
    }

    private boolean isActive(SessionEntity s) {
        try {
            return SessionState.valueOf(s.getStatus()).isActive();
        } catch (Exception e) {
            return false;
        }
    }

    private VerifyRunEntity requireRun(long id) {
        return runRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "verify run 不存在: " + id));
    }

    private static String parseSha(String lsRemoteOutput) {
        if (lsRemoteOutput == null || lsRemoteOutput.isBlank()) {
            return null;
        }
        String firstLine = lsRemoteOutput.strip().split("\n")[0].trim();
        int sp = firstLine.indexOf('\t');
        if (sp < 0) {
            sp = firstLine.indexOf(' ');
        }
        String sha = sp > 0 ? firstLine.substring(0, sp) : firstLine;
        return sha.matches("[0-9a-fA-F]{40}") || sha.matches("[0-9a-fA-F]{64}") ? sha : null;
    }

    private String writeTimings(Map<String, Long> timings) {
        try {
            return timings.isEmpty() ? null : mapper.writeValueAsString(timings);
        } catch (Exception e) {
            return null;
        }
    }

    private VerifyRunView toView(VerifyRunEntity e) {
        return new VerifyRunView(e.getId(), e.getRequirementId(), e.getProjectId(),
                e.getTriggerType(), e.getTriggeredBy(), e.getEnvironmentId(), e.getCommitSha(),
                e.getPushStatus(), e.getStage(), e.getStatus(), e.getModulesHint(),
                e.getFilesChanged(), e.getDurationMs(), e.getStageTimingsJson(),
                e.getFailureSummary(), e.getNote(), e.getBuildNodeId(), e.getEnvNodeId(),
                e.getTestRunId(), e.getLogsText(), e.getCreatedAt(), e.getFinishedAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.strip();
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "-";
        }
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    /** 全链日志缓冲（线程安全 + 上限截断防 @Lob 爆列） */
    static final class LogBuffer implements java.util.function.Consumer<String> {
        private static final int CAP = 240 * 1024;
        private final StringBuilder sb = new StringBuilder();

        @Override
        public synchronized void accept(String line) {
            if (line == null) {
                return;
            }
            if (sb.length() < CAP) {
                sb.append(line, 0, Math.min(line.length(), CAP - sb.length())).append('\n');
            }
        }

        synchronized String text() {
            return sb.isEmpty() ? null : sb.toString();
        }

        synchronized String tail(int max) {
            int from = Math.max(0, sb.length() - max);
            return sb.substring(from);
        }
    }
}
