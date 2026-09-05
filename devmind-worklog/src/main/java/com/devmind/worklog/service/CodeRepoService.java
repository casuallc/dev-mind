package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.worklog.dto.RepoRequest;
import com.devmind.worklog.dto.RepoView;
import com.devmind.worklog.model.GitRepositoryEntity;
import com.devmind.worklog.model.WorklogRepoSubscriptionEntity;
import com.devmind.worklog.repo.GitRepositoryRepository;
import com.devmind.worklog.repo.WorklogRepoSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CAP-28 FR-01/02：全局代码仓库登记（写操作仅 ADMIN，由 SecurityConfig 路由规则保证）
 * 与用户参与勾选。登记时 git rev-parse 校验本地路径确为 git 仓库。
 */
@Service
public class CodeRepoService {

    private final GitRepositoryRepository repoRepo;
    private final WorklogRepoSubscriptionRepository subRepo;
    private final IdentityService identity;

    public CodeRepoService(GitRepositoryRepository repoRepo,
                           WorklogRepoSubscriptionRepository subRepo,
                           IdentityService identity) {
        this.repoRepo = repoRepo;
        this.subRepo = subRepo;
        this.identity = identity;
    }

    /** 全员可见；附当前用户 subscribed 标记。 */
    public List<RepoView> list() {
        String me = identity.currentActor();
        Set<Long> subscribed = new HashSet<>(
                subRepo.findByUserId(me).stream().map(WorklogRepoSubscriptionEntity::getRepoId).toList());
        return repoRepo.findAll().stream()
                .map(e -> RepoView.of(e, subscribed.contains(e.getId())))
                .toList();
    }

    /** 本人勾选中的 ACTIVE 仓库（git 扫描用）。 */
    public List<GitRepositoryEntity> subscribedActiveRepos(String username) {
        List<Long> ids = subRepo.findByUserId(username).stream()
                .map(WorklogRepoSubscriptionEntity::getRepoId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return repoRepo.findAllById(ids).stream()
                .filter(r -> GitRepositoryEntity.STATUS_ACTIVE.equals(r.getStatus()))
                .toList();
    }

    @Transactional
    public RepoView create(RepoRequest req) {
        String path = validateGitRepo(req.localPath());
        if (repoRepo.findByLocalPath(path).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该路径已登记: " + path);
        }
        GitRepositoryEntity e = new GitRepositoryEntity();
        e.setName(req.name().strip());
        e.setLocalPath(path);
        e.setRemoteUrl(req.remoteUrl());
        e.setDefaultBranch(req.defaultBranch());
        e.setStatus(GitRepositoryEntity.STATUS_ACTIVE);
        e.setCreatedBy(identity.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return RepoView.of(repoRepo.save(e), false);
    }

    @Transactional
    public RepoView update(Long id, RepoRequest req) {
        GitRepositoryEntity e = repoRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + id));
        String path = req.localPath() == null || req.localPath().equals(e.getLocalPath())
                ? e.getLocalPath() : validateGitRepo(req.localPath());
        if (!path.equals(e.getLocalPath()) && repoRepo.findByLocalPath(path).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "该路径已登记: " + path);
        }
        e.setName(req.name().strip());
        e.setLocalPath(path);
        e.setRemoteUrl(req.remoteUrl());
        e.setDefaultBranch(req.defaultBranch());
        if (req.status() != null) {
            if (!GitRepositoryEntity.STATUS_ACTIVE.equals(req.status())
                    && !GitRepositoryEntity.STATUS_DISABLED.equals(req.status())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "status 仅支持 ACTIVE/DISABLED");
            }
            e.setStatus(req.status());
        }
        e.setUpdatedAt(Instant.now());
        boolean subscribed = subRepo.findByUserIdAndRepoId(identity.currentActor(), id).isPresent();
        return RepoView.of(repoRepo.save(e), subscribed);
    }

    @Transactional
    public void delete(Long id) {
        if (!repoRepo.existsById(id)) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + id);
        }
        subRepo.deleteByRepoId(id);
        repoRepo.deleteById(id);
    }

    /** 本人勾选/取消勾选。 */
    @Transactional
    public void setSubscription(Long repoId, boolean subscribed) {
        String me = identity.currentActor();
        if (subscribed) {
            if (!repoRepo.existsById(repoId)) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + repoId);
            }
            if (subRepo.findByUserIdAndRepoId(me, repoId).isEmpty()) {
                WorklogRepoSubscriptionEntity s = new WorklogRepoSubscriptionEntity();
                s.setUserId(me);
                s.setRepoId(repoId);
                s.setCreatedAt(Instant.now());
                subRepo.save(s);
            }
        } else {
            subRepo.deleteByUserIdAndRepoId(me, repoId);
        }
    }

    /** 路径存在且 git rev-parse 通过；返回规范化绝对路径。 */
    private String validateGitRepo(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "localPath 不能为空");
        }
        Path p = Path.of(localPath.strip());
        if (!Files.isDirectory(p)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "路径不存在或不是目录: " + p);
        }
        GitCli.Result r = GitCli.run(p, 15, "git", "rev-parse", "--is-inside-work-tree");
        GitCli.requireOk(r, List.of("git", "rev-parse"));
        if (!"true".equals(r.out().strip())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不是 git 仓库: " + p);
        }
        return p.toAbsolutePath().normalize().toString();
    }
}
