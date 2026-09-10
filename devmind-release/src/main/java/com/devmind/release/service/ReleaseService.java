package com.devmind.release.service;

import com.devmind.auth.IdentityService;
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
import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.PlatformIntegrationHook;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.runner.LocalStepRunner;
import com.devmind.execution.template.ScriptTemplateService;
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

/**
 * CAP-11 发版编排（复用 P0-1 执行底座）：创建（版本解析 FR-02/幂等/校验构建产物）→ 异步执行
 * （LOCAL=LocalStepRunner 渲染模板正文在主库路径执行；AGENT=CAP-36 渲染后经 exec 帧下发 runner 节点，
 * 主库 remoteUrl + Git 凭据随帧下发准备构建工作区）
 * → git tag v&lt;version&gt;（FR-04）→ 状态机 PLANNED/RUNNING/SUCCESS/FAILED/ROLLED_BACK
 * → 通知（FR-07 成功 P1 / 失败 P0）；回滚=删 tag + 移除 Nexus 制品引用（FR-06）。
 * 关键陷阱同构建/部署：execute() 不标 @Transactional，save() 自身事务即时提交后异步 run() 才能看到未提交行。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final IdentityService identityService;
    private final ExecutorService releaseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ReleaseRepository repo;
    private final ReleaseConfigRepository releaseConfigRepo;
    private final ProjectService projectService;
    private final AgentNodeRouter agentNodeRouter;
    private final AgentNodeStepRunner agentNodeRunner;
    private final ScriptTemplateService templateService;
    private final BuildService buildService;
    private final LocalStepRunner localRunner;
    private final NotificationService notificationService;
    private final ExecutionLogHub hub;
    /** CAP-18 FR-06 可选钩子：devmind-integration 装配时存在，发版成功后 push tag + 建平台 Release */
    private final org.springframework.beans.factory.ObjectProvider<PlatformIntegrationHook> integrationHook;
    /** CAP-26 可选 SPI：devmind-integration 装配时存在，tag 前 fetch 服务端 clone 保鲜 */
    private final org.springframework.beans.factory.ObjectProvider<RepoGitGateway> repoGitGateway;

    public ReleaseService(ReleaseRepository repo,
                          ReleaseConfigRepository releaseConfigRepo,
                          ProjectService projectService,
                          AgentNodeRouter agentNodeRouter,
                          AgentNodeStepRunner agentNodeRunner,
                          ScriptTemplateService templateService,
                          BuildService buildService,
                          LocalStepRunner localRunner,
                          NotificationService notificationService,
                          ExecutionLogHub hub,
                           IdentityService identityService,
                           org.springframework.beans.factory.ObjectProvider<PlatformIntegrationHook> integrationHook,
                           org.springframework.beans.factory.ObjectProvider<RepoGitGateway> repoGitGateway) {
        this.integrationHook = integrationHook;
        this.repoGitGateway = repoGitGateway;
        this.identityService = identityService;
        this.repo = repo;
        this.releaseConfigRepo = releaseConfigRepo;
        this.projectService = projectService;
        this.agentNodeRouter = agentNodeRouter;
        this.agentNodeRunner = agentNodeRunner;
        this.templateService = templateService;
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
        // CAP-36 目标节点：显式 > 项目发版配置 > 节点路由链（项目默认 > 平台默认），fail-fast 409
        String agentNodeId = req.agentNodeId() != null && !req.agentNodeId().isBlank()
                ? req.agentNodeId().trim() : cfg.getAgentNodeId();
        if ("AGENT".equals(executor)) {
            if (agentNodeId == null || agentNodeId.isBlank()) {
                agentNodeId = agentNodeRouter.route(null,
                        projectService.requireProject(req.projectId()).agentNodeId(), null);
            }
            agentNodeRouter.requireExecCapable(agentNodeId);
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
        r.setWorkItemId(blankToNull(req.workItemId()));
        r.setBuildId(req.buildId());
        r.setReleaseVersion(version);
        r.setStatus(ReleaseEntity.PLANNED);
        r.setArtifactRef(artifact);
        r.setNexusRef(blankToNull(cfg.getNexusRepo()) == null ? null : cfg.getNexusRepo().trim() + ":" + version);
        r.setTagName("v" + version);
        r.setExecutor(executor);
        r.setAgentNodeId(agentNodeId);
        r.setCreatedBy(identityService.currentActor());
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
            Map<String, String> env = new HashMap<>();
            env.put("RELEASE_PROJECT_ID", nz(r.getProjectId()));
            env.put("RELEASE_VERSION", nz(r.getReleaseVersion()));
            env.put("RELEASE_ARTIFACT", nz(r.getArtifactRef()));
            env.put("RELEASE_REPOSITORY", nz(cfg.getNexusRepo()));
            env.put("RELEASE_TAG", nz(r.getTagName()));
            boolean ok;
            String err;
            if ("AGENT".equals(r.getExecutor())) {
                // CAP-36：模板渲染（白名单 capability=release）→ exec 帧下发 runner 节点；
                // 主库有 http(s) remoteUrl 时随帧下发凭据准备构建工作区（checkout 到关联构建 commit）
                sink.accept("===== 节点执行发版脚本（节点 " + r.getAgentNodeId() + " · 模板 "
                        + cfg.getScriptTemplateRef() + "）=====");
                String rendered = templateService.renderTemplate(r.getProjectId(),
                        cfg.getScriptTemplateRef().trim(), "release", params);
                AgentExecCommand.Repo execRepo = buildExecRepo(r, sink);
                StepResult res = agentNodeRunner.runStep(r.getAgentNodeId(), r.getProjectId(),
                        "release-" + r.getId(), 0,
                        new StepSpec("release-push", rendered, null, "release"), env, execRepo, sink);
                ok = res.ok();
                err = ok ? null : (res.error() == null ? "exit=" + res.exitCode() : res.error());
            } else {
                String rendered = templateService.renderTemplate(r.getProjectId(),
                        cfg.getScriptTemplateRef().trim(), "release", params);
                ProjectRepoEntity primary = projectService.primaryRepo(r.getProjectId());
                String repoPath = primary.getPath();
                sink.accept("===== 本地执行发版脚本（仓库 " + repoPath + "）=====");
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
                    // CAP-26：tag 基准 = 关联构建 commit（最准确）→ fetch 后 origin/<baseBranch> → 本地 HEAD
                    String target = tagTarget(r, repoPath, sink);
                    tagged = target != null
                            ? gitExec(repoPath, "tag", "-a", tag, "-m", "release " + nz(r.getReleaseVersion()), target)
                            : gitExec(repoPath, "tag", "-a", tag, "-m", "release " + nz(r.getReleaseVersion()));
                    sink.accept(tagged ? "[git tag] " + tag + (target != null ? " @ " + target.substring(0, Math.min(8, target.length())) : "")
                            : "[git tag] " + tag + " 创建失败（可能已存在）");
                } else {
                    sink.accept("[git tag] 主库不可用，跳过打 tag");
                }
                r.setStatus(ReleaseEntity.SUCCESS);
                // CAP-18 FR-06 可选钩子：push tag 到绑定远程 + 建平台 Release（未绑定时返回 null 静默跳过；
                // 失败只进日志，不回转发版状态）
                PlatformIntegrationHook hook = integrationHook.getIfAvailable();
                if (hook != null) {
                    try {
                        String integrationResult = hook.onReleaseSuccess(r.getProjectId(), r.getId(),
                                tag, r.getReleaseVersion(),
                                "发版 #" + r.getId() + " v" + r.getReleaseVersion()
                                        + (r.getArtifactRef() != null ? " · 产物 " + r.getArtifactRef() : ""));
                        if (integrationResult != null) {
                            sink.accept(integrationResult);
                        }
                    } catch (Exception e) {
                        log.warn("平台集成钩子异常（发版已成功，不回转）: {}", e.getMessage());
                        sink.accept("[集成] 平台 Release 推送异常：" + e.getMessage());
                    }
                }
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
        if (r.getWorkItemId() != null) {
            p.put("workItemId", r.getWorkItemId());
        }
        return p;
    }

    /**
     * CAP-36 AGENT 发版的工作区描述：remoteUrl 取项目主库，token 经 RepoGitGateway 按 CAP-35 身份链解析
     * （发版人个人 PAT → 项目绑定 Integration），随 exec 帧下发（runner 仅内存持有）。
     * checkout 基准 = 关联构建 commit（无则分支 HEAD）。无 remoteUrl / 非 http(s) 远端返回 null——
     * runner 不准备代码，退化到节点项目映射目录执行（日志留痕）。
     */
    private AgentExecCommand.Repo buildExecRepo(ReleaseEntity r, Consumer<String> sink) {
        String remoteUrl;
        String branch = null;
        try {
            ProjectRepoEntity primary = projectService.primaryRepo(r.getProjectId());
            remoteUrl = primary == null ? null : primary.getRemoteUrl();
            branch = primary == null ? null : primary.getDefaultBranch();
        } catch (Exception e) {
            remoteUrl = null;
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            sink.accept("[工作区] 项目主库无 remoteUrl，runner 侧不拉取代码（使用节点本地映射目录）");
            return null;
        }
        String url = remoteUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sink.accept("[工作区] 非 http(s) 远端（" + hostOf(url) + "），runner 侧不拉取代码（使用节点本地映射目录）");
            return null;
        }
        String commit = null;
        if (r.getBuildId() != null) {
            try {
                commit = buildService.requireBuild(r.getBuildId()).getCommit();
            } catch (Exception e) {
                log.debug("关联构建 {} 读取 commit 失败(忽略): {}", r.getBuildId(), e.getMessage());
            }
        }
        String token = null;
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw != null) {
            token = gw.resolveToken(r.getCreatedBy(), hostOf(url), r.getProjectId()).orElse(null);
        }
        if (token == null) {
            sink.accept("[工作区] 未解析到 Git 凭据（个人 PAT / 项目绑定集成均无），按匿名克隆");
        } else {
            sink.accept("[工作区] Git 凭据已随帧下发（runner 仅内存持有）");
        }
        return new AgentExecCommand.Repo(url, branch, commit, token);
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
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

    /** CAP-36：executor ∈ LOCAL|AGENT；历史 REMOTE 一律映射为 AGENT（SSH 通道已下线） */
    private String normalizeExecutor(String executor) {
        if (executor == null) {
            return "LOCAL";
        }
        String t = executor.trim();
        return "AGENT".equalsIgnoreCase(t) || "REMOTE".equalsIgnoreCase(t) ? "AGENT" : "LOCAL";
    }

    private String primaryRepoPath(String projectId) {
        try {
            return projectService.primaryRepo(projectId).getPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * CAP-26 tag 基准解析：关联构建 commit（发版产物的真实来源，最准确）→
     * fetch 成功后 origin/<baseBranch> → null（调用方按现状打本地 HEAD）。
     * fetch 失败不阻断发版（推送已成功，仅降级基准来源并留日志）。
     */
    private String tagTarget(ReleaseEntity r, String repoPath, Consumer<String> sink) {
        if (r.getBuildId() != null) {
            try {
                String c = buildService.requireBuild(r.getBuildId()).getCommit();
                if (c != null && !c.isBlank()) {
                    return c;
                }
            } catch (Exception e) {
                log.debug("关联构建 {} 读取失败(忽略): {}", r.getBuildId(), e.getMessage());
            }
        }
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw != null) {
            try {
                String base = projectService.requireProject(r.getProjectId()).baseBranch();
                if (gw.fetch(repoPath, base, r.getCreatedBy())) {
                    String c = gitExecOut(repoPath, "rev-parse", "origin/" + base);
                    if (c != null && !c.isBlank()) {
                        sink.accept("[同步] fetch 完成，tag 基准 origin/" + base);
                        return c;
                    }
                }
            } catch (Exception e) {
                log.warn("发版 tag 前 fetch 失败(降级为本地 HEAD): {}", e.getMessage());
                sink.accept("[同步] fetch 失败，tag 基准回退本地 HEAD: " + e.getMessage());
            }
        }
        return null;
    }

    /** git 命令取 stdout（失败/空输出返回 null），与 {@link #gitExec} 互补 */
    private String gitExecOut(String repoPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoPath);
        for (String a : args) {
            cmd.add(a);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && !out.isEmpty() ? out : null;
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

    private void notify(ReleaseEntity r, NotificationLevel level, String title, String body) {
        try {
            notificationService.emit(new NotificationDraft(level, "release", title, body,
                    "release", String.valueOf(r.getId()), r.getProjectId(), List.of()));
        } catch (Exception e) {
            log.warn("发版通知发送失败: {}", e.getMessage());
        }
    }

    private ReleaseView toView(ReleaseEntity r) {
        return new ReleaseView(r.getId(), r.getProjectId(), r.getWorkItemId(), r.getBuildId(),
                r.getReleaseVersion(), r.getStatus(), r.getArtifactRef(), r.getNexusRef(), r.getTagName(),
                r.getExecutor(), r.getAgentNodeId(), r.getRollbackOf(), r.getErrorSummary(), r.getCreatedBy(),
                r.getStartedAt(), r.getFinishedAt(), r.getCreatedAt());
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
