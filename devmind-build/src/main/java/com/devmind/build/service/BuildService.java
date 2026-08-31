package com.devmind.build.service;

import com.devmind.build.dto.BuildView;
import com.devmind.build.dto.TriggerRequest;
import com.devmind.build.model.BuildEntity;
import com.devmind.build.model.BuildStep;
import com.devmind.build.repo.BuildRepository;
import com.devmind.build.runner.LocalBuildRunner;
import com.devmind.build.runner.RemoteBuildRunner;
import com.devmind.build.runner.StepResult;
import com.devmind.build.ws.BuildLogHub;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.RequirementService;
import com.devmind.project.model.BuildStepEntity;
import com.devmind.project.model.Project;
import com.devmind.project.repo.BuildStepRepository;
import com.devmind.serveradapter.service.ServerOperationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-08 构建编排：触发（并发限制/commit branch 解析/步骤快照）→ 虚拟线程异步执行 →
 * 本地 bash 或远程模板执行 → 产物登记（FR-04）→ 状态机 QUEUED/RUNNING/SUCCESS/FAILED（FR-06）。
 * 日志行实时经 {@link BuildLogHub} 广播（FR-05），步骤边界与结束时持久化全量留存。
 */
@Service
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);
    private static final Pattern ARTIFACT = Pattern.compile("(?im)^artifact[:=]\\s*(.+)$");

    private final ExecutorService buildExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final BuildRepository repo;
    private final BuildConfigService configService;
    private final BuildStepRepository stepRepo;
    private final ProjectService projectService;
    private final RequirementService requirementService;
    private final ServerOperationService serverOpService;
    private final LocalBuildRunner localRunner;
    private final RemoteBuildRunner remoteRunner;
    private final BuildLogHub hub;
    private final ObjectMapper mapper;

    public BuildService(BuildRepository repo,
                        BuildConfigService configService,
                        BuildStepRepository stepRepo,
                        ProjectService projectService,
                        RequirementService requirementService,
                        ServerOperationService serverOpService,
                        LocalBuildRunner localRunner,
                        RemoteBuildRunner remoteRunner,
                        BuildLogHub hub,
                        ObjectMapper mapper) {
        this.repo = repo;
        this.configService = configService;
        this.stepRepo = stepRepo;
        this.projectService = projectService;
        this.requirementService = requirementService;
        this.serverOpService = serverOpService;
        this.localRunner = localRunner;
        this.remoteRunner = remoteRunner;
        this.hub = hub;
        this.mapper = mapper;
    }

    @PreDestroy
    public void shutdown() {
        buildExecutor.shutdownNow();
    }

    // ---------------- 触发 ----------------

    /** 不用 @Transactional：save() 自身事务立即提交，否则异步 run() 在另一连接看不到未提交行 */
    public BuildView trigger(String projectId, TriggerRequest req) {
        Project project = projectService.requireProject(projectId);
        if (req.requirementId() != null && !req.requirementId().isBlank()) {
            // P0-6 关联约定：requirementId 升级为外键语义，须属于该项目
            requirementService.requireEntity(projectId, req.requirementId());
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
            if (commit == null || commit.isBlank()) {
                commit = gitExec(project.repoPath(), "rev-parse", "HEAD");
            }
            if (branch == null || branch.isBlank()) {
                branch = gitExec(project.repoPath(), "symbolic-ref", "--short", "HEAD");
            }
        }
        BuildEntity b = new BuildEntity();
        b.setProjectId(projectId);
        b.setRequirementId(req.requirementId());
        b.setCommit(commit);
        b.setBranch(branch);
        b.setExecutor(executor);
        b.setRemoteServerId(serverId);
        b.setStepsSnapshot(snapshotJson(steps));
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

        StringBuilder logs = new StringBuilder();
        Consumer<String> sink = line -> {
            synchronized (logs) {
                logs.append(line).append('\n');
            }
            hub.publish(buildId, line);
        };
        Project project = null;
        try {
            project = projectService.requireProject(b.getProjectId());
        } catch (Exception e) {
            // 项目可能已删除：远程执行仍可用，本地执行报错
        }
        List<BuildStep> steps = parseSteps(b.getStepsSnapshot());
        boolean ok = true;
        String err = null;
        int exit = 0;
        try {
            for (int i = 0; i < steps.size(); i++) {
                BuildStep s = steps.get(i);
                String label = s.name() == null || s.name().isBlank() ? String.valueOf(i + 1) : s.name();
                sink.accept("===== 步骤 " + (i + 1) + "/" + steps.size() + " · " + label + " =====");
                StepResult r;
                if ("REMOTE".equals(b.getExecutor())) {
                    r = remoteRunner.runStep(b.getRemoteServerId(), s, b.getCommit(), b.getBranch(), sink);
                } else {
                    r = project == null
                            ? new StepResult(false, -1, "项目不存在，无法本地构建")
                            : localRunner.runStep(Path.of(project.repoPath()), s, b.getCommit(), b.getBranch(), b.getProjectId(), sink);
                }
                if (!r.ok()) {
                    ok = false;
                    err = r.error() != null ? r.error() : "exit=" + r.exitCode();
                    exit = r.exitCode();
                    sink.accept("[构建失败] " + err);
                    break;
                }
                flushLogs(b, logs); // 步骤边界持久化
            }
            if (ok) {
                b.setStatus(BuildEntity.SUCCESS);
                b.setExitCode(0);
                b.setArtifactRef(captureArtifact(logs.toString()));
                if (b.getArtifactRef() != null) {
                    sink.accept("[产物登记] " + b.getArtifactRef());
                }
            } else {
                b.setStatus(BuildEntity.FAILED);
                b.setExitCode(exit);
                b.setErrorSummary(truncate(err, 2000));
            }
        } catch (Exception e) {
            log.warn("构建 {} 异常: {}", buildId, e.toString());
            b.setStatus(BuildEntity.FAILED);
            b.setExitCode(-1);
            b.setErrorSummary("构建异常: " + rootMessage(e));
        } finally {
            b.setLogsText(logs.toString());
            b.setFinishedAt(Instant.now());
            repo.save(b);
            hub.done(buildId, b.getStatus());
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

    private String snapshotJson(List<BuildStepEntity> steps) {
        try {
            List<BuildStep> list = steps.stream()
                    .map(e -> new BuildStep(e.getName(), e.getCommand(), e.getWorkingDir(), e.getLocation()))
                    .toList();
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "步骤快照序列化失败");
        }
    }

    private List<BuildStep> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = mapper.readTree(json);
            List<BuildStep> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    out.add(new BuildStep(text(n, "name"), text(n, "command"), text(n, "workingDir"), text(n, "location")));
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
        return new BuildView(b.getId(), b.getProjectId(), b.getRequirementId(), b.getCommit(), b.getBranch(),
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
