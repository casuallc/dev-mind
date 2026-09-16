package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitRepoCatalog;
import com.devmind.worklog.dto.RepoView;
import com.devmind.worklog.model.WorklogRepoSubscriptionEntity;
import com.devmind.worklog.repo.WorklogRepoSubscriptionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-28 FR-02 用户参与勾选（订阅数据归本模块）。
 * CAP-29 起仓库登记表移到 devmind-project 全局管理，本服务只经
 * {@link GitRepoCatalog} SPI 读注册表（ObjectProvider 探测，缺席时列表为空、勾选 404）。
 *
 * <p>订阅行可勾选扫描分支（{@code branches} 换行分隔存储）；空 = 跟随仓库默认分支，
 * 保持 CAP-28 原行为。</p>
 */
@Service
public class CodeRepoService {

    private final WorklogRepoSubscriptionRepository subRepo;
    private final IdentityService identity;
    private final ObjectProvider<GitRepoCatalog> catalog;

    public CodeRepoService(WorklogRepoSubscriptionRepository subRepo,
                           IdentityService identity,
                           ObjectProvider<GitRepoCatalog> catalog) {
        this.subRepo = subRepo;
        this.identity = identity;
        this.catalog = catalog;
    }

    /** 本人某仓库的订阅：repo 引用 + 勾选分支（空 = 跟随默认分支）。 */
    public record SubscribedRepo(GitRepoCatalog.RepoRef repo, List<String> branches) {}

    /** 全员可见；附当前用户 subscribed 标记与勾选分支。 */
    public List<RepoView> list() {
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null) {
            return List.of();
        }
        String me = identity.currentActor();
        Map<Long, WorklogRepoSubscriptionEntity> subs = subRepo.findByUserId(me).stream()
                .collect(Collectors.toMap(WorklogRepoSubscriptionEntity::getRepoId, Function.identity()));
        return cat.listAll().stream()
                .map(r -> {
                    WorklogRepoSubscriptionEntity sub = subs.get(r.id());
                    return RepoView.of(r, sub != null, sub == null ? List.of() : splitBranches(sub.getBranches()));
                })
                .toList();
    }

    /** 本人勾选中的 ACTIVE 仓库（git 扫描用）。 */
    public List<SubscribedRepo> subscribedActiveRepos(String username) {
        return subscribedRepos(username).stream()
                // 状态常量归 project 模块实体，此处用字面量防跨模块依赖
                .filter(s -> "ACTIVE".equals(s.repo().status()))
                .toList();
    }

    /** 本人勾选的全部仓库（含 DISABLED；扫描诊断用，让停用仓库也能说明原因）。 */
    public List<SubscribedRepo> subscribedRepos(String username) {
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null) {
            return List.of();
        }
        List<WorklogRepoSubscriptionEntity> subs = subRepo.findByUserId(username);
        if (subs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> branchesByRepo = subs.stream()
                .collect(Collectors.toMap(WorklogRepoSubscriptionEntity::getRepoId,
                        s -> splitBranches(s.getBranches())));
        return cat.listByIds(branchesByRepo.keySet()).stream()
                .map(r -> new SubscribedRepo(r, branchesByRepo.getOrDefault(r.id(), List.of())))
                .toList();
    }

    /**
     * 本人勾选/取消勾选。branches 语义：null = 不改动既有选择（新行缺省跟随默认分支）；
     * 空列表 = 清除选择恢复跟随默认分支；非空 = 勾选这些分支扫描。
     */
    @Transactional
    public void setSubscription(Long repoId, boolean subscribed, List<String> branches) {
        String me = identity.currentActor();
        if (!subscribed) {
            subRepo.deleteByUserIdAndRepoId(me, repoId);
            return;
        }
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null || cat.listByIds(List.of(repoId)).isEmpty()) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + repoId);
        }
        WorklogRepoSubscriptionEntity s = subRepo.findByUserIdAndRepoId(me, repoId).orElseGet(() -> {
            WorklogRepoSubscriptionEntity n = new WorklogRepoSubscriptionEntity();
            n.setUserId(me);
            n.setRepoId(repoId);
            n.setCreatedAt(Instant.now());
            return n;
        });
        if (branches != null) {
            s.setBranches(joinBranches(branches));
        }
        subRepo.save(s);
    }

    /** 换行分隔存储（与 git_repositories.branches 同口径）；去空白去重，空返回 null。 */
    private static String joinBranches(List<String> branches) {
        LinkedHashSet<String> cleaned = branches.stream()
                .filter(b -> b != null && !b.isBlank())
                .map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return cleaned.isEmpty() ? null : String.join("\n", cleaned);
    }

    private static List<String> splitBranches(String branches) {
        if (branches == null || branches.isBlank()) {
            return List.of();
        }
        return branches.lines().map(String::strip).filter(s -> !s.isBlank()).toList();
    }
}
