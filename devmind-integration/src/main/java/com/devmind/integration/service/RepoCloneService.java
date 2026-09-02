package com.devmind.integration.service;

import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.RepoView;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * CAP-23 仓库克隆编排：监听 project 模块的 {@code project.repo.clone-requested} 事件
 * （反向触发防依赖环），虚拟线程异步执行 git clone，状态机 NONE→CLONING→READY/FAILED，
 * 日志经 ExecutionLogHub 实时广播并落 project_repos.clone_logs。
 *
 * <p>token 不出模块边界：在此解密、仅内存传入 {@link GitRemoteOps}（进程参数注入 +
 * 输出脱敏 + clone 后 set-url 清除 .git/config 残留）。</p>
 */
@Service
public class RepoCloneService {

    private static final Logger log = LoggerFactory.getLogger(RepoCloneService.class);
    /** project 模块发布的克隆请求事件类型 */
    public static final String EVENT_CLONE_REQUESTED = "project.repo.clone-requested";
    /** WS topic 前缀：clone-<repoId> */
    public static final String TOPIC_PREFIX = "clone-";

    private final ProjectRepoRepository repoRepo;
    private final ProjectService projectService;
    private final IntegrationRepository integrationRepo;
    private final IntegrationService integrationService;
    private final GitRemoteOps gitOps;
    private final ExecutionLogHub hub;
    private final ExecutorService cloneExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** 在途克隆（并发守卫；package-private 供单测预置） */
    final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public RepoCloneService(ProjectRepoRepository repoRepo,
                            ProjectService projectService,
                            IntegrationRepository integrationRepo,
                            IntegrationService integrationService,
                            GitRemoteOps gitOps,
                            ExecutionLogHub hub) {
        this.repoRepo = repoRepo;
        this.projectService = projectService;
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
        this.gitOps = gitOps;
        this.hub = hub;
    }

    @PreDestroy
    void shutdown() {
        cloneExecutor.shutdownNow();
    }

    // ---------------- 事件入口 ----------------

    /** 项目/仓库创建时 project 模块发布的克隆请求（同步分发，这里只投递到虚拟线程）。 */
    @EventListener
    public void onCloneRequested(SimpleDomainEvent event) {
        if (!EVENT_CLONE_REQUESTED.equals(event.type()) || !"PROJECT_REPO".equals(event.entityType())) {
            return;
        }
        try {
            requestClone(event.projectId(), Long.parseLong(event.entityId()));
        } catch (DevMindException e) {
            // 并发重复事件/状态已流转：忽略（重试走显式端点）
            log.debug("克隆请求事件跳过: repoId={} reason={}", event.entityId(), e.getMessage());
        }
    }

    // ---------------- 触发/重试 ----------------

    /**
     * 触发（或重试）单库克隆；并发在途抛 CONFLICT；非 CLONE 来源抛 BAD_REQUEST。
     * 并发守卫只看内存 in-flight（不看 CLONING 状态）：创建事件路径下行已是 CLONING，
     * 且进程重启后残留的 CLONING 行必须可重试恢复。
     */
    public RepoView requestClone(String projectId, Long repoId) {
        ProjectRepoEntity repo = requireRepo(projectId, repoId);
        if (!ProjectRepoEntity.SOURCE_CLONE.equals(repo.getSourceType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "本地路径仓库无需克隆: " + repoId);
        }
        if (!inFlight.add(repoId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该仓库正在克隆中: " + repoId);
        }
        try {
            cloneExecutor.submit(() -> run(projectId, repoId));
        } catch (RuntimeException e) {
            inFlight.remove(repoId);
            throw e;
        }
        return toView(repo);
    }

    /** 重试项目内全部 FAILED 库。 */
    public List<RepoView> retryFailed(String projectId) {
        List<RepoView> views = new ArrayList<>();
        for (ProjectRepoEntity r : repoRepo.findByProjectIdOrderBySortOrderAscIdAsc(projectId)) {
            if (ProjectRepoEntity.SOURCE_CLONE.equals(r.getSourceType())
                    && ProjectRepoEntity.CLONE_FAILED.equals(r.getCloneStatus())) {
                views.add(requestClone(projectId, r.getId()));
            }
        }
        return views;
    }

    /** 克隆日志回放（对齐 getBuildLogs 先例）。 */
    public String cloneLogs(String projectId, Long repoId) {
        ProjectRepoEntity repo = requireRepo(projectId, repoId);
        return repo.getCloneLogs() == null ? "" : repo.getCloneLogs();
    }

    // ---------------- 异步执行（禁 @Transactional：靠 save 自身事务即时提交） ----------------

    private void run(String projectId, Long repoId) {
        String topic = TOPIC_PREFIX + repoId;
        try {
            ProjectRepoEntity repo = requireRepo(projectId, repoId);
            repo.setCloneStatus(ProjectRepoEntity.CLONE_CLONING);
            repo.setCloneError(null);
            repo.setCloneLogs("");
            repo.setUpdatedAt(Instant.now());
            repoRepo.save(repo);
            mirrorPrimary(projectId);

            StringBuilder acc = new StringBuilder();
            Consumer<String> sink = line -> {
                acc.append(line).append('\n');
                hub.publishLog(topic, line);
            };
            sink.accept("$ git clone " + repo.getRemoteUrl() + " " + repo.getPath());

            GitRemoteOps.GitResult result;
            try {
                String token = resolveToken(repo);
                result = gitOps.cloneRepo(repo.getRemoteUrl(), token, repo.getPath(),
                        repo.getDefaultBranch(), sink);
            } catch (DevMindException e) {
                result = new GitRemoteOps.GitResult(false, e.getMessage());
                sink.accept("克隆前置校验失败: " + e.getMessage());
            }

            if (result.ok()) {
                // FR-05：未指定默认分支时探测 origin/HEAD 回写
                if (repo.getDefaultBranch() == null || repo.getDefaultBranch().isBlank()) {
                    GitRemoteOps.GitResult head = gitOps.remoteHeadBranch(repo.getPath());
                    if (head.ok()) {
                        repo.setDefaultBranch(head.output());
                        sink.accept("默认分支: " + head.output());
                    }
                }
                repo.setCloneStatus(ProjectRepoEntity.CLONE_READY);
                repo.setClonedAt(Instant.now());
                repo.setCloneError(null);
            } else {
                repo.setCloneStatus(ProjectRepoEntity.CLONE_FAILED);
                repo.setCloneError(truncate(result.output(), 1000));
                sink.accept("克隆失败: " + result.output());
            }
            repo.setCloneLogs(acc.toString());
            repo.setUpdatedAt(Instant.now());
            repoRepo.save(repo);
            mirrorPrimary(projectId);
            hub.publishEvent(topic, "cloneStatus", toView(repo));
            hub.done(topic, repo.getCloneStatus());
            log.info("仓库克隆结束: projectId={} repoId={} status={}", projectId, repoId, repo.getCloneStatus());
        } catch (Exception e) {
            log.warn("仓库克隆异常: projectId={} repoId={} err={}", projectId, repoId, e.getMessage());
            try {
                ProjectRepoEntity repo = repoRepo.findById(repoId).orElse(null);
                if (repo != null) {
                    repo.setCloneStatus(ProjectRepoEntity.CLONE_FAILED);
                    repo.setCloneError(truncate("克隆异常: " + e.getMessage(), 1000));
                    repo.setUpdatedAt(Instant.now());
                    repoRepo.save(repo);
                    mirrorPrimary(projectId);
                }
            } catch (Exception ignored) {
                // 兜底失败不再抛
            }
            hub.done(topic, ProjectRepoEntity.CLONE_FAILED);
        } finally {
            inFlight.remove(repoId);
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 解析克隆凭据（仅内存，不进日志）。null = 匿名克隆公开仓库。
     * 校验：集成存在、ENABLED、类型 GITLAB/GITHUB、base_url host 与 remoteUrl host 一致
     * （防拿 A 平台 token 撞 B 平台）。
     */
    private String resolveToken(ProjectRepoEntity repo) {
        Long integrationId = repo.getIntegrationId();
        if (integrationId == null) {
            return null;
        }
        if (repo.getRemoteUrl() != null && repo.getRemoteUrl().trim().startsWith("file://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "file:// 仅支持匿名克隆（不可选择集成实例）");
        }
        IntegrationEntity e = integrationRepo.findById(integrationId)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "集成实例不存在: " + integrationId));
        if (!IntegrationEntity.STATUS_ENABLED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "集成实例已禁用: " + e.getName());
        }
        if (!IntegrationEntity.TYPE_GITLAB.equals(e.getType()) && !IntegrationEntity.TYPE_GITHUB.equals(e.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "克隆仅支持 GitLab/GitHub 集成实例: " + e.getType());
        }
        String repoHost = hostOf(repo.getRemoteUrl());
        String baseHost = hostOf(e.getBaseUrl());
        if (repoHost != null && baseHost != null && !repoHost.equalsIgnoreCase(baseHost)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成实例地址（" + baseHost + "）与仓库远端主机（" + repoHost + "）不一致");
        }
        return integrationService.tokenOf(e);
    }

    private String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return java.net.URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 主库时回写 projects.clone_status 镜像（syncPrimaryMirror 内部从主库记录取值）。 */
    private void mirrorPrimary(String projectId) {
        projectService.syncPrimaryMirror(projectId);
    }

    private ProjectRepoEntity requireRepo(String projectId, Long repoId) {
        return repoRepo.findById(repoId)
                .filter(x -> x.getProjectId().equals(projectId))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "项目仓库不存在: " + repoId));
    }

    private RepoView toView(ProjectRepoEntity r) {
        return new RepoView(r.getId(), r.getProjectId(), r.getName(), r.getPath(), r.getSourceType(),
                r.getRemoteUrl(), r.getIntegrationId(),
                r.getDefaultBranch(), r.getRole(), Boolean.TRUE.equals(r.getIsPrimary()), r.getSortOrder(),
                r.getCloneStatus(), r.getCloneError(), r.getClonedAt(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
