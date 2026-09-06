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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CAP-28 FR-02 用户参与勾选（订阅数据归本模块）。
 * CAP-29 起仓库登记表移到 devmind-project 全局管理，本服务只经
 * {@link GitRepoCatalog} SPI 读注册表（ObjectProvider 探测，缺席时列表为空、勾选 404）。
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

    /** 全员可见；附当前用户 subscribed 标记。 */
    public List<RepoView> list() {
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null) {
            return List.of();
        }
        String me = identity.currentActor();
        Set<Long> subscribed = new HashSet<>(
                subRepo.findByUserId(me).stream().map(WorklogRepoSubscriptionEntity::getRepoId).toList());
        return cat.listAll().stream()
                .map(r -> RepoView.of(r, subscribed.contains(r.id())))
                .toList();
    }

    /** 本人勾选中的 ACTIVE 仓库（git 扫描用）。 */
    public List<GitRepoCatalog.RepoRef> subscribedActiveRepos(String username) {
        return subscribedRepos(username).stream()
                // 状态常量归 project 模块实体，此处用字面量防跨模块依赖
                .filter(r -> "ACTIVE".equals(r.status()))
                .toList();
    }

    /** 本人勾选的全部仓库（含 DISABLED；扫描诊断用，让停用仓库也能说明原因）。 */
    public List<GitRepoCatalog.RepoRef> subscribedRepos(String username) {
        GitRepoCatalog cat = catalog.getIfAvailable();
        if (cat == null) {
            return List.of();
        }
        List<Long> ids = subRepo.findByUserId(username).stream()
                .map(WorklogRepoSubscriptionEntity::getRepoId).toList();
        return cat.listByIds(ids);
    }

    /** 本人勾选/取消勾选。 */
    @Transactional
    public void setSubscription(Long repoId, boolean subscribed) {
        String me = identity.currentActor();
        if (subscribed) {
            GitRepoCatalog cat = catalog.getIfAvailable();
            if (cat == null || cat.listByIds(List.of(repoId)).isEmpty()) {
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
}