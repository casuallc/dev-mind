package com.devmind.integration.service;

import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.GitRepositoryEntity;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.GitRepositoryRepository;
import com.devmind.project.repo.ProjectRepoRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CAP-29 全局仓库克隆/抓取引擎：监听 project 模块的 {@code gitrepo.clone-requested} 事件
 * （反向触发防依赖环，同 CAP-23 先例），虚拟线程异步执行 git clone；
 * fetchOne 手动/定时抓取远端分支（refspec + prune）、刷新默认分支与分支列表。
 *
 * <p>状态机归全局行：NONE→CLONING→READY/FAILED；每次迁移<b>扇出镜像</b>到所有
 * {@code project_repos.git_repo_id} 关联行（cloneStatus/cloneError/clonedAt）并逐项目
 * syncPrimaryMirror——消费方（WorktreeManager/构建/发版）零改动。</p>
 *
 * <p>token 不出模块边界：经 {@link CloneTokenResolver} 仅内存传入 {@link GitRemoteOps}。</p>
 */
@Service
public class GitRepoSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitRepoSyncService.class);
    /** project 模块发布的全局仓库克隆请求事件类型 */
    public static final String EVENT_CLONE_REQUESTED = "gitrepo.clone-requested";

    private final GitRepositoryRepository gitRepoRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final ProjectService projectService;
    private final CloneTokenResolver tokenResolver;
    private final GitRemoteOps gitOps;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 在途克隆/抓取（并发守卫；重启后残留的 CLONING 行必须可重试，故只看内存） */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public GitRepoSyncService(GitRepositoryRepository gitRepoRepo,
                              ProjectRepoRepository projectRepoRepo,
                              ProjectService projectService,
                              CloneTokenResolver tokenResolver,
                              GitRemoteOps gitOps) {
        this.gitRepoRepo = gitRepoRepo;
        this.projectRepoRepo = projectRepoRepo;
        this.projectService = projectService;
        this.tokenResolver = tokenResolver;
        this.gitOps = gitOps;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------- 事件入口 ----------------

    /** 全局仓库登记（CLONE 新建）时 project 模块发布的克隆请求。 */
    @EventListener
    public void onCloneRequested(SimpleDomainEvent event) {
        if (!EVENT_CLONE_REQUESTED.equals(event.type()) || !"GIT_REPO".equals(event.entityType())) {
            return;
        }
        try {
            requestClone(Long.parseLong(event.entityId()));
        } catch (DevMindException e) {
            // 并发重复事件/状态已流转：忽略（重试走显式端点）
            log.debug("全局仓库克隆事件跳过: gitRepoId={} reason={}", event.entityId(), e.getMessage());
        }
    }

    // ---------------- 克隆 ----------------

    /** 触发（或重试）全局仓库克隆；并发在途抛 CONFLICT；非 CLONE 来源抛 BAD_REQUEST。 */
    public void requestClone(Long gitRepoId) {
        GitRepositoryEntity repo = requireRepo(gitRepoId);
        if (!GitRepositoryEntity.SOURCE_CLONE.equals(repo.getSourceType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "本地路径仓库无需克隆: " + gitRepoId);
        }
        if (!inFlight.add(gitRepoId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该仓库正在克隆中: " + gitRepoId);
        }
        try {
            executor.submit(() -> runClone(gitRepoId));
        } catch (RuntimeException e) {
            inFlight.remove(gitRepoId);
            throw e;
        }
    }

    /** 异步执行（禁 @Transactional：靠 save 自身事务即时提交，否则状态迁移对外不可见）。 */
    private void runClone(Long gitRepoId) {
        try {
            GitRepositoryEntity repo = requireRepo(gitRepoId);
            repo.setCloneStatus(GitRepositoryEntity.CLONE_CLONING);
            repo.setCloneError(null);
            repo.setUpdatedAt(Instant.now());
            gitRepoRepo.save(repo);
            mirrorToProjectRepos(repo);

            GitRemoteOps.GitResult result;
            try {
                String token = tokenResolver.resolve(repo.getIntegrationId(), repo.getRemoteUrl());
                result = gitOps.cloneRepo(repo.getRemoteUrl(), token, repo.getLocalPath(),
                        repo.getDefaultBranch(), null);
            } catch (DevMindException e) {
                result = new GitRemoteOps.GitResult(false, e.getMessage());
            }

            if (result.ok()) {
                // 未指定默认分支时探测 origin/HEAD 回写（同 CAP-23 FR-05）
                if (repo.getDefaultBranch() == null || repo.getDefaultBranch().isBlank()) {
                    GitRemoteOps.GitResult head = gitOps.remoteHeadBranch(repo.getLocalPath());
                    if (head.ok()) {
                        repo.setDefaultBranch(head.output());
                    }
                }
                repo.setCloneStatus(GitRepositoryEntity.CLONE_READY);
                repo.setCloneError(null);
                refreshBranches(repo);
                repo.setLastFetchAt(Instant.now());
                repo.setLastFetchError(null);
            } else {
                repo.setCloneStatus(GitRepositoryEntity.CLONE_FAILED);
                repo.setCloneError(truncate(result.output(), 1000));
            }
            repo.setUpdatedAt(Instant.now());
            gitRepoRepo.save(repo);
            mirrorToProjectRepos(repo);
            log.info("全局仓库克隆结束: gitRepoId={} status={}", gitRepoId, repo.getCloneStatus());
        } catch (Exception e) {
            log.warn("全局仓库克隆异常: gitRepoId={} err={}", gitRepoId, e.getMessage());
            try {
                GitRepositoryEntity repo = gitRepoRepo.findById(gitRepoId).orElse(null);
                if (repo != null) {
                    repo.setCloneStatus(GitRepositoryEntity.CLONE_FAILED);
                    repo.setCloneError(truncate("克隆异常: " + e.getMessage(), 1000));
                    repo.setUpdatedAt(Instant.now());
                    gitRepoRepo.save(repo);
                    mirrorToProjectRepos(repo);
                }
            } catch (Exception ignored) {
                // 兜底失败不再抛
            }
        } finally {
            inFlight.remove(gitRepoId);
        }
    }

    // ---------------- 抓取（手动 + 定时） ----------------

    /**
     * 抓取单库：fetch 远端全部分支（+prune）→ 刷新分支列表与默认分支 → 记 lastFetchAt/Error。
     * 同步执行（手动端点与调度器共用；调度器逐库调用，单库失败记 lastFetchError 不中断）。
     */
    public void fetchOne(Long gitRepoId) {
        GitRepositoryEntity repo = requireRepo(gitRepoId);
        if (!GitRepositoryEntity.SOURCE_CLONE.equals(repo.getSourceType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "本地路径仓库不支持远端抓取: " + gitRepoId);
        }
        if (!GitRepositoryEntity.CLONE_READY.equals(repo.getCloneStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "仓库克隆未就绪（" + repo.getCloneStatus() + "），请先克隆成功: " + gitRepoId);
        }
        if (!inFlight.add(gitRepoId)) {
            throw new DevMindException(ErrorCode.CONFLICT, "该仓库正在同步中: " + gitRepoId);
        }
        try {
            doFetch(repo);
        } finally {
            inFlight.remove(gitRepoId);
        }
    }

    /** 调度器入口：遍历全部可抓取行（CLONE + READY + ACTIVE）。 */
    public void fetchAll() {
        List<GitRepositoryEntity> repos = gitRepoRepo.findBySourceTypeAndCloneStatusAndStatus(
                GitRepositoryEntity.SOURCE_CLONE, GitRepositoryEntity.CLONE_READY,
                GitRepositoryEntity.STATUS_ACTIVE);
        for (GitRepositoryEntity repo : repos) {
            if (!inFlight.add(repo.getId())) {
                continue;
            }
            try {
                doFetch(repo);
            } catch (Exception e) {
                log.warn("定时抓取失败: gitRepoId={} err={}", repo.getId(), e.getMessage());
            } finally {
                inFlight.remove(repo.getId());
            }
        }
    }

    private void doFetch(GitRepositoryEntity repo) {
        String token = tokenResolver.resolve(repo.getIntegrationId(), repo.getRemoteUrl());
        GitRemoteOps.GitResult result = gitOps.fetchAllRefs(repo.getLocalPath(), repo.getRemoteUrl(), token);
        if (result.ok()) {
            refreshBranches(repo);
            // 远端 HEAD 漂移时更新默认分支
            GitRemoteOps.GitResult head = gitOps.remoteHeadBranch(repo.getLocalPath());
            if (head.ok() && !head.output().equals(repo.getDefaultBranch())) {
                repo.setDefaultBranch(head.output());
                mirrorDefaultBranchToProjectRepos(repo);
            }
            // 本地检出分支不随 fetch 移动：快进到 origin/<默认分支>，供扫描/构建读到最新代码
            if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
                GitRemoteOps.GitResult ff = gitOps.ffOnly(repo.getLocalPath(),
                        "origin/" + repo.getDefaultBranch().trim());
                if (!ff.ok()) {
                    log.info("默认分支快进跳过（非 ff，不影响抓取结果）: gitRepoId={} branch={}",
                            repo.getId(), repo.getDefaultBranch());
                }
            }
            repo.setLastFetchAt(Instant.now());
            repo.setLastFetchError(null);
        } else {
            repo.setLastFetchAt(Instant.now());
            repo.setLastFetchError(truncate(result.output(), 1000));
        }
        repo.setUpdatedAt(Instant.now());
        gitRepoRepo.save(repo);
        log.info("全局仓库抓取结束: gitRepoId={} ok={}", repo.getId(), result.ok());
    }

    /** 刷新远程分支列表（换行分隔存储；剥 origin/ 前缀，滤掉 HEAD 指针与裸 origin 行）。 */
    private void refreshBranches(GitRepositoryEntity repo) {
        GitRemoteOps.GitResult refs = gitOps.listRemoteBranches(repo.getLocalPath());
        if (!refs.ok()) {
            return;
        }
        String branches = refs.output().lines()
                .map(String::trim)
                .filter(s -> !s.isBlank() && !s.equals("origin") && !s.endsWith("/HEAD"))
                .map(s -> s.startsWith("origin/") ? s.substring("origin/".length()) : s)
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        repo.setBranches(branches.isBlank() ? null : branches);
    }

    // ---------------- 状态镜像（扇出到 project_repos 关联行） ----------------

    /** 克隆状态扇出：cloneStatus/cloneError/clonedAt 镜像到所有关联行 + 逐项目主库镜像。 */
    private void mirrorToProjectRepos(GitRepositoryEntity repo) {
        List<ProjectRepoEntity> linked = projectRepoRepo.findByGitRepoId(repo.getId());
        if (linked.isEmpty()) {
            return;
        }
        Set<String> projectIds = new HashSet<>();
        for (ProjectRepoEntity pr : linked) {
            pr.setCloneStatus(repo.getCloneStatus());
            pr.setCloneError(repo.getCloneError());
            if (GitRepositoryEntity.CLONE_READY.equals(repo.getCloneStatus()) && pr.getClonedAt() == null) {
                pr.setClonedAt(Instant.now());
            }
            if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()
                    && (pr.getDefaultBranch() == null || pr.getDefaultBranch().isBlank())) {
                pr.setDefaultBranch(repo.getDefaultBranch());
            }
            pr.setUpdatedAt(Instant.now());
            projectRepoRepo.save(pr);
            projectIds.add(pr.getProjectId());
        }
        projectIds.forEach(projectService::syncPrimaryMirror);
    }

    /** 远端 HEAD 漂移时把新默认分支镜像到关联行（无条件覆盖，保持与全局行一致）。 */
    private void mirrorDefaultBranchToProjectRepos(GitRepositoryEntity repo) {
        Set<String> projectIds = new HashSet<>();
        for (ProjectRepoEntity pr : projectRepoRepo.findByGitRepoId(repo.getId())) {
            pr.setDefaultBranch(repo.getDefaultBranch());
            pr.setUpdatedAt(Instant.now());
            projectRepoRepo.save(pr);
            projectIds.add(pr.getProjectId());
        }
        projectIds.forEach(projectService::syncPrimaryMirror);
    }

    private GitRepositoryEntity requireRepo(Long gitRepoId) {
        return gitRepoRepo.findById(gitRepoId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "全局仓库不存在: " + gitRepoId));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
