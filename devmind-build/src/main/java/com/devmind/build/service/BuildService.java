package com.devmind.build.service;

import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.model.BuildEntity;
import com.devmind.build.repo.BuildRepository;
import com.devmind.artifact.ArtifactService;
import com.devmind.auth.IdentityService;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.execution.engine.StepChainRunner;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.LocalStepRunner;
import com.devmind.execution.runner.RemoteStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.project.ProjectService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.BuildStepEntity;
import com.devmind.project.model.Project;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.serveradapter.service.ServerOperationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-08 构建编排：触发（并发限制/commit branch 解析/步骤快照）→ 虚拟线程异步执行 →
 * 产物登记（FR-04）→ 状态机 QUEUED/RUNNING/SUCCESS/FAILED（FR-06）。
 * 执行层自 P0-1 起委托统一执行底座：步骤链由 {@link StepChainRunner} 调度，
 * 本地/远程执行由 {@link LocalStepRunner}/{@link RemoteStepRunner} 承担，
 * 日志实时经统一 {@link ExecutionLogHub} 广播（FR-05），步骤边界与结束时持久化全量留存。
 * 本类只保留构建业务语义：配置、并发上限、commit/branch 解析、产物登记、历史查询。
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    private static final Pattern ARTIFACT = Pattern.compile("(?im)^artifact[:=]\\s*(.+)$");

    private final ExecutorService buildExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** CAP-26：同项目构建串行锁（checkout 改写共享 clone 工作区，防互踩） */
    private final Map<String, Object> projectLocks = new ConcurrentHashMap<>();

    private final ArtifactService artifactService;
    private final IdentityService identityService;
    private final DomainEventPublisher eventPublisher;
    private final BuildRepository repo;
    private final BuildConfigService configService;
    private final BuildStepRepository stepRepo;
    private final ProjectService projectService;
    private final WorkItemService workItemService;
    private final ServerOperationService serverOpService;
    private final StepChainRunner chainRunner;
    private final LocalStepRunner localRunner;
    private final RemoteStepRunner remoteRunner;
    private final ExecutionLogHub hub;
    private final ObjectMapper mapper;
    private final ObjectProvider<RepoGitGateway> repoGitGateway;

    public BuildService(ArtifactService artifactService,
                        IdentityService identityService,
                        DomainEventPublisher eventPublisher,
                        BuildRepository repo,
                        BuildConfigService configService,
                        BuildStepRepository stepRepo,
                        ProjectService projectService,
                        WorkItemService workItemService,
                        ServerOperationService serverOpService,
                        StepChainRunner chainRunner,
                        LocalStepRunner localRunner,
                        RemoteStepRunner remoteRunner,
                        ExecutionLogHub hub,
                        ObjectMapper mapper,
                        ObjectProvider<RepoGitGateway> repoGitGateway) {
        this.artifactService = artifactService;
        this.identityService = identityService;
        this.eventPublisher = eventPublisher;
        this.repo = repo;
        this.configService = configService;
        this.stepRepo = stepRepo;
        this.projectService = projectService;
        this.workItemService = workItemService;
        this.serverOpService = serverOpService;
        this.chainRunner = chainRunner;
        this.localRunner = localRunner;
        this.remoteRunner = remoteRunner;
        this.hub = hub;
        this.mapper = mapper;
        this.repoGitGateway = repoGitGateway;
    }

    @PreDestroy
    public void shutdown() {
        buildExecutor.shutdownNow();
    }

    // ---------------- 触发 ----------------

    /** 不用 @Transactional：save() 自身事务立即提交，否则异步 run() 在另一连接看不到未提交行 */
    public BuildView trigger(String projectId, TriggerRequest req) {
        Project project = projectService.requireProject(projectId);
        if (req.workItemId() != null && !req.workItemId().isBlank()) {
            // CAP-13 关联约定：workItemId 升级为外键语义，须属于该项目
            workItemService.requireEntity(projectId, req.workItemId());
        }
        com.devmind.build.dto.BuildConfigView cfg = configService.get(projectId);
        String executor = req.executor() != null && !req.executor().isBlank() ? req.executor().trim().toUpperCase() : cfg.executor();
        if (!"LOCAL".equals(executor) && !"REMOTE".equals(executor)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "executor 只能是 LOCAL 或 REMOTE");
        }
        Long serverId = req.remoteServerId() != null ? req.remoteServerId() : cfg.remoteServerId();
        if ("REMOTE".equals(executor)) {
            if (serverId == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "远程执行需指定目标服务器（配置或本次触发指定）");
            }
            serverOpService.requireServer(serverId);
        }
        List<BuildStepEntity> steps = stepRepo.findByProjectIdOrderBySortOrderAsc(projectId);
        if (steps.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目 " + projectId + " 未配置构建步骤（项目管理 → 构建配置）");
        }
        long active = repo.countByProjectIdAndStatusIn(projectId, List.of(BuildEntity.QUEUED, BuildEntity.RUNNING));
        if (active >= cfg.concurrencyLimit()) {
            throw new DevMindException(ErrorCode.CONFLICT, "并发构建数已达上限 " + cfg.concurrencyLimit() + "，请等待当前构建结束");
        }
        String commit = req.commit();
        String branch = req.branch();
        if ("LOCAL".equals(executor)) {
            // CAP-26：有 remoteUrl 的库先 fetch（失败即触发失败，不拿过时 HEAD 构建），
            // 基准一律取 origin/ 远端引用；纯本地库保持原行为
            boolean synced = syncBeforeExec(project, branch, identityService.currentActor());
            if (branch == null || branch.isBlank()) {
                branch = synced ? project.baseBranch()
                        : gitExec(project.repoPath(), "symbolic-ref", "--short", "HEAD");
            }
            if (commit == null || commit.isBlank()) {
                if (synced && branch != null && !branch.isBlank()) {
                    commit = gitExec(project.repoPath(), "rev-parse", "origin/" + branch);
                }
                if (commit == null || commit.isBlank()) {
                    commit = gitExec(project.repoPath(), "rev-parse", "HEAD");
                }
            }
        }
        BuildEntity b = new BuildEntity();
        b.setProjectId(projectId);
        b.setWorkItemId(req.workItemId());
        b.setCommit(commit);
        b.setBranch(branch);
        b.setExecutor(executor);
        b.setRemoteServerId(serverId);
        b.setStepsSnapshot(snapshotJson(steps));
        b.setCreatedBy(identityService.currentActor());
        b.setStatus(BuildEntity.QUEUED);
        b.setCreatedAt(Instant.now());
        BuildEntity saved = repo.save(b);
        buildExecutor.submit(() -> run(saved.getId()));
        return toView(saved);
    }

    // ---------------- 异步执行 ----------------

    private void run(Long buildId) {
        BuildEntity b = repo.findById(buildId).orElse(null);
        if (b == null) {
            return;
        }
        b.setStatus(BuildEntity.RUNNING);
        b.setStartedAt(Instant.now());
        repo.save(b);

        String topic = String.valueOf(buildId);
        StringBuilder logs = new StringBuilder();
        Consumer<String> sink = line -> {
            synchronized (logs) {
                logs.append(line).append('\n');
            }
            hub.publishLog(topic, line);
        };
        Project project = null;
        try {
            project = projectService.requireProject(b.getProjectId());
        } catch (Exception e) {
            // 项目可能已删除：远程执行仍可用，本地执行报错
        }
        List<StepSpec> steps = parseSteps(b.getStepsSnapshot());
        try {
            Project finalProject = project;
            StepChainRunner.ChainResult result;
            if (!"REMOTE".equals(b.getExecutor()) && finalProject != null) {
                // CAP-26 同项目构建串行：checkout 会改写共享 clone 工作区，项目级锁防互踩
                synchronized (projectLock(b.getProjectId())) {
                    checkoutSyncedCommit(b, finalProject, sink);
                    result = executeChain(b, finalProject, steps, sink, logs);
                }
            } else {
                result = executeChain(b, finalProject, steps, sink, logs);
            }

            if (result.ok()) {
                b.setStatus(BuildEntity.SUCCESS);
                b.setExitCode(0);
                b.setArtifactRef(captureArtifact(logs.toString()));
                if (b.getArtifactRef() != null) {
                    sink.accept("[产物登记] " + b.getArtifactRef());
                    // CAP-13：登记为 artifacts 表一等实体（artifactRef 字符串保留作兼容展示）
                    String requirementId = b.getWorkItemId() == null || b.getWorkItemId().isBlank() ? null
                            : workItemService.requireById(b.getWorkItemId()).getRequirementId();
                    artifactService.register(b.getProjectId(), b.getWorkItemId(), requirementId,
                            b.getArtifactRef(), ArtifactService.PRODUCER_BUILD, b.getId());
                }
            } else {
                b.setStatus(BuildEntity.FAILED);
                b.setExitCode(result.exitCode());
                b.setErrorSummary(truncate(result.error(), 2000));
            }
        } catch (Exception e) {
            log.warn("构建 {} 异常: {}", buildId, e.toString());
            b.setStatus(BuildEntity.FAILED);
            b.setExitCode(-1);
            b.setErrorSummary("构建异常: " + rootMessage(e));
        } finally {
            synchronized (logs) {
                b.setLogsText(logs.toString());
            }
            b.setFinishedAt(Instant.now());
            repo.save(b);
            hub.done(topic, b.getStatus());
            // P0-3 统一事件总线：构建结果广播（通知订阅，成功 P1 / 失败 P0）
            eventPublisher.publish(SimpleDomainEvent.of("build.completed", b.getProjectId(),
                    b.getWorkItemId(), b.getCreatedBy(),
                    "构建 #" + b.getId() + " " + b.getStatus()
                            + (b.getBranch() == null ? "" : "（" + b.getBranch() + "）"),
                    "BUILD", String.valueOf(b.getId()), BuildEntity.SUCCESS.equals(b.getStatus())));
        }
    }

    // ---------------- 查询 ----------------

    public BuildEntity requireBuild(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "构建记录不存在: " + id));
    }

    public BuildView get(Long id) {
        return toView(requireBuild(id));
    }

    public List<BuildView> history(String projectId, String status) {
        List<BuildEntity> list = status == null || status.isBlank()
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status.trim().toUpperCase());
        return list.stream().map(this::toView).toList();
    }

    public String logs(Long id) {
        return requireBuild(id).getLogsText();
    }

    /** 删除历史记录；运行中的构建拒绝（避免销毁执行中的任务上下文） */
    public void delete(Long id) {
        BuildEntity b = requireBuild(id);
        if (BuildEntity.RUNNING.equals(b.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "构建运行中不可删除");
        }
        repo.delete(b);
    }

    // ---------------- 内部 ----------------

    /** 原 run() 内的步骤链执行（LOCAL/REMOTE 分流），抽出以便 CAP-26 项目锁包裹。 */
    private StepChainRunner.ChainResult executeChain(BuildEntity b, Project project, List<StepSpec> steps,
                                                     Consumer<String> sink, StringBuilder logs) {
        return chainRunner.run(steps, (i, step, stepSink) -> {
            if ("REMOTE".equals(b.getExecutor())) {
                Map<String, String> params = new HashMap<>();
                if (b.getCommit() != null && !b.getCommit().isBlank()) {
                    params.put("commit", b.getCommit());
                }
                if (b.getBranch() != null && !b.getBranch().isBlank()) {
                    params.put("branch", b.getBranch());
                }
                return remoteRunner.runStep(b.getRemoteServerId(), step, params, "build", stepSink);
            }
            if (project == null) {
                return StepResult.failed(-1, "项目不存在，无法本地构建");
            }
            Map<String, String> env = new HashMap<>();
            env.put("BUILD_PROJECT_ID", b.getProjectId() == null ? "" : b.getProjectId());
            env.put("BUILD_COMMIT", b.getCommit() == null ? "" : b.getCommit());
            env.put("BUILD_BRANCH", b.getBranch() == null ? "" : b.getBranch());
            env.put("BUILD_STEP", step.name() == null ? "" : step.name());
            return localRunner.runStep(Path.of(project.repoPath()), step, env, stepSink);
        }, sink, i -> flushLogs(b, logs)); // 步骤边界持久化
    }

    /** CAP-26 触发期同步：有 remoteUrl 的库 fetch（失败即抛错=触发失败）；纯本地库/SPI 未装配返回 false */
    private boolean syncBeforeExec(Project project, String branch, String actor) {
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw == null) {
            return false;
        }
        return gw.fetch(project.repoPath(), branch, actor);
    }

    /**
     * CAP-26 执行期同步：再 fetch 一次（覆盖触发→执行间的新 push）后 checkout 到记录的 commit。
     * 纯本地库（无 remoteUrl）跳过，保持原行为。失败抛 DevMindException → 构建 FAILED（fail-fast）。
     */
    private void checkoutSyncedCommit(BuildEntity b, Project project, Consumer<String> sink) {
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw == null || b.getCommit() == null || b.getCommit().isBlank()) {
            return;
        }
        if (!gw.fetch(project.repoPath(), b.getBranch(), b.getCreatedBy())) {
            return;
        }
        String c = b.getCommit();
        sink.accept("[同步] fetch 完成，checkout " + c.substring(0, Math.min(8, c.length())));
        if (gitExec(project.repoPath(), "checkout", "--detach", c) == null) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "git checkout " + c + " 失败（工作区可能有未提交改动或该 commit 不存在）");
        }
    }

    private Object projectLock(String projectId) {
        return projectLocks.computeIfAbsent(projectId == null ? "" : projectId, k -> new Object());
    }

    private String snapshotJson(List<BuildStepEntity> steps) {
        try {
            List<StepSpec> list = steps.stream()
                    .map(e -> new StepSpec(e.getName(), e.getCommand(), e.getWorkingDir(), e.getLocation()))
                    .toList();
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "步骤快照序列化失败");
        }
    }

    private List<StepSpec> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = mapper.readTree(json);
            List<StepSpec> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    out.add(new StepSpec(text(n, "name"), text(n, "command"), text(n, "workingDir"), text(n, "location")));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void flushLogs(BuildEntity b, StringBuilder logs) {
        synchronized (logs) {
            b.setLogsText(logs.toString());
        }
        repo.save(b);
    }

    /** FR-04 产物登记：成功后在日志中识别 artifact= 或 artifact: 行（最后一个生效） */
    private String captureArtifact(String logs) {
        if (logs == null) {
            return null;
        }
        Matcher m = ARTIFACT.matcher(logs);
        String last = null;
        while (m.find()) {
            last = m.group(1).trim();
        }
        return last == null || last.isBlank() ? null : last;
    }

    private String gitExec(String repoPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoPath);
        for (String a : args) {
            cmd.add(a);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            return code == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    public BuildView toView(BuildEntity b) {
        return new BuildView(b.getId(), b.getProjectId(), b.getWorkItemId(), b.getCommit(), b.getBranch(),
                b.getExecutor(), b.getArtifactRef(), b.getStatus(), b.getExitCode(), b.getErrorSummary(),
                b.getStartedAt(), b.getFinishedAt(), b.getCreatedAt());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
