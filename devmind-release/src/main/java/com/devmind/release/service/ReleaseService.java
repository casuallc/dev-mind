package com.devmind.release.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devmind.build.model.BuildEntity;
import com.devmind.build.service.BuildService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.LocalStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.notification.dto.NotificationDraft;
import com.devmind.notification.model.NotificationLevel;
import com.devmind.notification.service.NotificationService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.model.ReleaseConfigEntity;
import com.devmind.project.repo.ReleaseConfigRepository;
import com.devmind.release.dto.CreateReleaseRequest;
import com.devmind.release.dto.ReleaseView;
import com.devmind.release.model.ReleaseEntity;
import com.devmind.release.repo.ReleaseRepository;
import com.devmind.serveradapter.model.ScriptTemplateEntity;
import com.devmind.serveradapter.repo.ScriptTemplateRepository;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.ExecResult;
import com.devmind.serveradapter.spi.ScriptTemplate;

/**
 * CAP-11 发版编排（复用 P0-1 执行底座）：创建（版本解析 FR-02/幂等/校验构建产物）→ 异步执行
 * （LOCAL=LocalStepRunner 渲染模板正文在主库路径执行；REMOTE=经 CAP-07 模板白名单 capability=release）
 * → git tag v&lt;version&gt;（FR-04）→ 状态机 PLANNED/RUNNING/SUCCESS/FAILED/ROLLED_BACK
 * → 通知（FR-07 成功 P1 / 失败 P0）；回滚=删 tag + 移除 Nexus 制品引用（FR-06）。
 * 关键陷阱同构建/部署：execute() 不标 @Transactional，save() 自身事务即时提交后异步 run() 才能看到未提交行。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ExecutorService releaseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ReleaseRepository repo;
    private final ReleaseConfigRepository releaseConfigRepo;
    private final ProjectService projectService;
    private final ServerOperationService serverOpService;
    private final ScriptTemplateRepository templateRepo;
    private final BuildService buildService;
    private final LocalStepRunner localRunner;
    private final NotificationService notificationService;
    private final ExecutionLogHub hub;

    public ReleaseService(ReleaseRepository repo,
                          ReleaseConfigRepository releaseConfigRepo,
                          ProjectService projectService,
                          ServerOperationService serverOpService,
                          ScriptTemplateRepository templateRepo,
                          BuildService buildService,
                          LocalStepRunner localRunner,
                          NotificationService notificationService,
                          ExecutionLogHub hub) {
        this.repo = repo;
        this.releaseConfigRepo = releaseConfigRepo;
        this.projectService = projectService;
        this.serverOpService = serverOpService;
        this.templateRepo = templateRepo;
        this.buildService = buildService;
        this.localRunner = localRunner;
        this.notificationService = notificationService;
        this.hub = hub;
    }

    @PreDestroy
    public void shutdown() {
        releaseExecutor.shutdownNow();
    }

    // ---------------- 创建（FR-01/02/03） ----------------

    public ReleaseView create(CreateReleaseRequest req) {
        if (req.projectId() == null || req.projectId().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
        }
        projectService.requireProject(req.projectId());
        ReleaseConfigEntity cfg = releaseConfigRepo.findByProjectId(req.projectId())
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "项目未配置发版（请在发版配置页填写 Nexus 仓库/推送模板/版本规则）"));

        String executor = normalizeExecutor(req.executor() != null && !req.executor().isBlank()
                ? req.executor() : cfg.getExecutor());
        Long serverId = req.serverId() != null ? req.serverId() : cfg.getRemoteServerId();
        if ("REMOTE".equals(executor)) {
            if (serverId == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "远程发版需指定目标服务器（executor=REMOTE 时 remoteServerId 必填）");
            }
            serverOpService.requireServer(serverId);
        }
        if (cfg.getScriptTemplateRef() == null || cfg.getScriptTemplateRef().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "项目未配置推送脚本模板引用（scriptTemplateRef）");
        }

        String version = resolveVersion(req.projectId(), cfg, req.version());
        String artifact = null;
        if (req.buildId() != null) {
            BuildEntity b = buildService.requireBuild(req.buildId());
            if (b.getArtifactRef() == null || b.getArtifactRef().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "构建 " + req.buildId() + " 未登记产物（artifactRef 为空），无法发版");
            }
            artifact = b.getArtifactRef();
        }

        // 幂等：同项目同版本且处于 PLANNED/RUNNING/SUCCESS 视为已发版（FR-02）
        if (!Boolean.TRUE.equals(req.force())) {
            Optional<ReleaseEntity> dup = repo.findByProjectIdAndReleaseVersion(req.projectId(), version);
            if (dup.isPresent() && !ReleaseEntity.ROLLED_BACK.equals(dup.get().getStatus())
                    && !ReleaseEntity.FAILED.equals(dup.get().getStatus())) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "版本 " + version + " 已存在发版 #" + dup.get().getId()
                                + "（" + dup.get().getStatus() + "），如需重发请传 force=true");
            }
        }

        ReleaseEntity r = new ReleaseEntity();
        r.setProjectId(req.projectId());
        r.setRequirementId(blankToNull(req.requirementId()));
        r.setBuildId(req.buildId());
        r.setReleaseVersion(version);
        r.setStatus(ReleaseEntity.PLANNED);
        r.setArtifactRef(artifact);
        r.setNexusRef(blankToNull(cfg.getNexusRepo()) == null ? null : cfg.getNexusRepo().trim() + ":" + version);
        r.setTagName("v" + version);
        r.setExecutor(executor);
        r.setServerId(serverId);
        r.setCreatedBy("user");
        r.setCreatedAt(Instant.now());
        return toView(repo.save(r));
    }

    /** 执行：不用 @Transactional（同构建/部署），save 自身事务即时提交后异步 run() */
    public ReleaseView execute(Long id) {
        ReleaseEntity r = require(id);
        if (!ReleaseEntity.PLANNED.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "只有待执行（PLANNED）的发版可执行");
        }
        r.setStatus(ReleaseEntity.RUNNING);
        r.setStartedAt(Instant.now());
        repo.save(r);
        releaseExecutor.submit(() -> run(r.getId()));
        return toView(r);
    }

    /** 回滚（FR-06）：删 git tag + 移除 Nexus 制品引用（MVP：置空 nexusRef），同步完成 */
    public ReleaseView rollback(Long id) {
        ReleaseEntity r = require(id);
        if (ReleaseEntity.RUNNING.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "发版运行中不可回滚");
        }
        if (ReleaseEntity.ROLLED_BACK.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "该发版已回滚");
        }
        String repoPath = primaryRepoPath(r.getProjectId());
        String tag = r.getTagName();
        boolean removed = false;
        if (repoPath != null && tag != null && !tag.isBlank()) {
            removed = gitExec(repoPath, "tag", "-d", tag);
        }
        r.setNexusRef(null);
        r.setStatus(ReleaseEntity.ROLLED_BACK);
        r.setFinishedAt(Instant.now());
        repo.save(r);
        notify(r, NotificationLevel.P1, "发版已回滚 #" + r.getId() + " v" + r.getReleaseVersion(),
                "已移除 Nexus 制品引用" + (removed ? " · 已删除 tag " + tag : " · tag 删除失败/主库不可用"));
        return toView(r);
    }

    // ---------------- 异步执行 ----------------

    private void run(Long releaseId) {
        ReleaseEntity r = repo.findById(releaseId).orElse(null);
        if (r == null) {
            return;
        }
        ReleaseConfigEntity cfg = releaseConfigRepo.findByProjectId(r.getProjectId()).orElse(null);
        StringBuilder logs = new StringBuilder();
        Consumer<String> sink = line -> {
            synchronized (logs) {
                logs.append(line).append('\n');
            }
            hub.publishLog(topic(releaseId), line);
        };
        try {
            if (cfg == null) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "项目发版配置已不存在");
            }
            Map<String, String> params = paramsOf(r, cfg);
            boolean ok;
            String err;
            if ("REMOTE".equals(r.getExecutor())) {
                sink.accept("===== 远程执行发版脚本（服务器 " + r.getServerId() + " · 模板 " + cfg.getScriptTemplateRef() + "）=====");
                ExecResult res = serverOpService.execute(r.getServerId(), cfg.getScriptTemplateRef().trim(), params, "release");
                streamOut(sink, res.stdout(), res.stderr());
                ok = res.success();
                err = ok ? null : failureReason(res);
            } else {
                ScriptTemplateEntity tplEntity = templateRepo.findByProjectIdAndCode(
                                r.getProjectId(), cfg.getScriptTemplateRef().trim())
                        .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                                "项目 " + r.getProjectId() + " 无模板 " + cfg.getScriptTemplateRef().trim()
                                        + "（白名单外不可执行）"));
                ScriptTemplate tpl = serverOpService.toDomain(tplEntity);
                checkRequired(tpl, params);
                String rendered = tpl.render(params);
                ProjectRepoEntity primary = projectService.primaryRepo(r.getProjectId());
                String repoPath = primary.getPath();
                sink.accept("===== 本地执行发版脚本（仓库 " + repoPath + "）=====");
                Map<String, String> env = new HashMap<>();
                env.put("RELEASE_PROJECT_ID", nz(r.getProjectId()));
                env.put("RELEASE_VERSION", nz(r.getReleaseVersion()));
                env.put("RELEASE_ARTIFACT", nz(r.getArtifactRef()));
                env.put("RELEASE_REPOSITORY", nz(cfg.getNexusRepo()));
                env.put("RELEASE_TAG", nz(r.getTagName()));
                StepResult res = localRunner.runStep(Path.of(repoPath),
                        new StepSpec("release-push", rendered, null, "LOCAL"), env, sink);
                ok = res.ok();
                err = ok ? null : (res.error() == null ? "exit=" + res.exitCode() : res.error());
            }

            if (ok) {
                // FR-04 git tag（主库不可用时降级为日志提示，不阻断已成功的推送）
                String repoPath = primaryRepoPath(r.getProjectId());
                String tag = r.getTagName();
                boolean tagged = false;
                if (repoPath != null && tag != null && !tag.isBlank()) {
                    tagged = gitExec(repoPath, "tag", "-a", tag, "-m", "release " + nz(r.getReleaseVersion()));
                    sink.accept(tagged ? "[git tag] " + tag : "[git tag] " + tag + " 创建失败（可能已存在）");
                } else {
                    sink.accept("[git tag] 主库不可用，跳过打 tag");
                }
                r.setStatus(ReleaseEntity.SUCCESS);
                notify(r, NotificationLevel.P1, "发版成功 #" + r.getId() + " v" + r.getReleaseVersion(),
                        "项目 " + r.getProjectId() + (tagged ? " · tag " + tag : ""));
            } else {
                r.setStatus(ReleaseEntity.FAILED);
                r.setErrorSummary(truncate(err, 2000));
                notify(r, NotificationLevel.P0, "发版失败 #" + r.getId() + " v" + r.getReleaseVersion(),
                        "原因: " + err);
            }
        } catch (Exception e) {
            log.warn("发版 {} 异常: {}", releaseId, e.toString());
            r.setStatus(ReleaseEntity.FAILED);
            r.setErrorSummary("发版异常: " + rootMessage(e));
            notify(r, NotificationLevel.P0, "发版异常 #" + r.getId(), rootMessage(e));
        } finally {
            synchronized (logs) {
                r.setLogsText(logs.toString());
            }
            r.setFinishedAt(Instant.now());
            repo.save(r);
            hub.done(topic(releaseId), r.getStatus());
        }
    }

    // ---------------- 查询 ----------------

    public ReleaseEntity require(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "发版记录不存在: " + id));
    }

    public ReleaseView get(Long id) {
        return toView(require(id));
    }

    public List<ReleaseView> history(String projectId, String status) {
        List<ReleaseEntity> list = status == null || status.isBlank()
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status.trim().toUpperCase());
        return list.stream().map(this::toView).toList();
    }

    public String logs(Long id) {
        return require(id).getLogsText();
    }

    public void delete(Long id) {
        ReleaseEntity r = require(id);
        if (ReleaseEntity.RUNNING.equals(r.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, "发版运行中不可删除");
        }
        repo.delete(r);
    }

    // ---------------- 内部 ----------------

    private Map<String, String> paramsOf(ReleaseEntity r, ReleaseConfigEntity cfg) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("projectId", r.getProjectId());
        p.put("version", nz(r.getReleaseVersion()));
        p.put("artifact", nz(r.getArtifactRef()));
        p.put("repository", nz(cfg.getNexusRepo()));
        p.put("tag", nz(r.getTagName()));
        if (r.getBuildId() != null) {
            p.put("buildId", String.valueOf(r.getBuildId()));
        }
        if (r.getRequirementId() != null) {
            p.put("requirementId", r.getRequirementId());
        }
        return p;
    }

    private void checkRequired(ScriptTemplate tpl, Map<String, String> params) {
        for (ScriptTemplate.ParamSpec p : tpl.params()) {
            if (!p.required()) {
                continue;
            }
            String v = params != null ? params.get(p.name()) : null;
            boolean hasDefault = p.defaultValue() != null && !p.defaultValue().isBlank();
            if ((v == null || v.isBlank()) && !hasDefault) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "模板 " + tpl.code() + " 缺少必填参数: " + p.name());
            }
        }
    }

    /** FR-02 版本解析：显式 version 优先；否则版本规则为可递增 semver（如 1.0.0）时对最近一次同主线发版 patch+1 */
    private String resolveVersion(String projectId, ReleaseConfigEntity cfg, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String rule = cfg.getVersionRule();
        if (rule == null || rule.isBlank() || !rule.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "未指定版本号，且项目版本规则不是可递增 semver（如 1.0.0），请在发版请求中显式传 version");
        }
        String[] parts = rule.split("\\.");
        String major = parts[0];
        String minor = parts[1];
        int basePatch;
        try {
            basePatch = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "版本规则 patch 段非数字: " + rule);
        }
        int next = basePatch;
        for (ReleaseEntity r : repo.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            String v = r.getReleaseVersion();
            if (v == null) {
                continue;
            }
            String[] p = v.split("\\.");
            if (p.length == 3 && p[0].equals(major) && p[1].equals(minor)) {
                try {
                    next = Math.max(Integer.parseInt(p[2]) + 1, basePatch);
                    break;
                } catch (NumberFormatException ignored) {
                    // 非数字 patch 忽略，取规则基线
                }
            }
        }
        return major + "." + minor + "." + next;
    }

    private String normalizeExecutor(String executor) {
        return executor != null && "REMOTE".equalsIgnoreCase(executor.trim()) ? "REMOTE" : "LOCAL";
    }

    private String primaryRepoPath(String projectId) {
        try {
            return projectService.primaryRepo(projectId).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** git 命令（-C 指定仓库）；返回是否成功（如 git tag 已存在会返回 false） */
    private boolean gitExec(String repoPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoPath);
        for (String a : args) {
            cmd.add(a);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行底座 WS topic：发版用 releaseId 字符串（与 /ws/releases/{id}/stream 对应） */
    private String topic(Long releaseId) {
        return String.valueOf(releaseId);
    }

    private String failureReason(ExecResult r) {
        String e = firstLine(r.stderr());
        if (e != null && !e.isBlank()) {
            return e;
        }
        e = lastLine(r.stdout());
        if (e != null && !e.isBlank()) {
            return e;
        }
        return "exit=" + r.exitCode();
    }

    private void streamOut(Consumer<String> sink, String stdout, String stderr) {
        if (stdout != null && !stdout.isBlank()) {
            for (String l : stdout.split("\\R")) {
                sink.accept(l);
            }
        }
        if (stderr != null && !stderr.isBlank()) {
            for (String l : stderr.split("\\R")) {
                sink.accept("[stderr] " + l);
            }
        }
    }

    private void notify(ReleaseEntity r, NotificationLevel level, String title, String body) {
        try {
            notificationService.emit(new NotificationDraft(level, "release", title, body,
                    "release", String.valueOf(r.getId()), List.of()));
        } catch (Exception e) {
            log.warn("发版通知发送失败: {}", e.getMessage());
        }
    }

    private ReleaseView toView(ReleaseEntity r) {
        return new ReleaseView(r.getId(), r.getProjectId(), r.getRequirementId(), r.getBuildId(),
                r.getReleaseVersion(), r.getStatus(), r.getArtifactRef(), r.getNexusRef(), r.getTagName(),
                r.getExecutor(), r.getServerId(), r.getRollbackOf(), r.getErrorSummary(), r.getCreatedBy(),
                r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt());
    }

    private String firstLine(String s) {
        if (s == null) {
            return null;
        }
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                return l.trim();
            }
        }
        return null;
    }

    private String lastLine(String s) {
        if (s == null) {
            return null;
        }
        String last = null;
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                last = l.trim();
            }
        }
        return last;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String nz(String s) {
        return s == null ? "" : s;
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
