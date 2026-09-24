package com.devmind.bookmark.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import org.springframework.stereotype.Component;

/**
 * CAP-64 FR-08 归属口径的唯一实现点（照搬 CAP-62 先例：chat 的 requireOwned/isAdmin）。
 *
 * <p>读路径 = owner 或 ADMIN；写路径 = 仅 owner（ADMIN 不可写）。越权一律按「不存在」处理（404），
 * 不暴露资源存在性。判定全部在服务层，Controller 不做 owner 校验。</p>
 *
 * <p>异步线程（批量探测）内 {@link #owner()} 会退化成 local，故异步任务一律不调本类——
 * owner 在入口线程取好后显式传下去。</p>
 */
@Component
public class BookmarkOwnership {

    private final IdentityService identityService;

    public BookmarkOwnership(IdentityService identityService) {
        this.identityService = identityService;
    }

    /** 当前操作者（users.username） */
    public String owner() {
        return identityService.currentActor();
    }

    /** 是否管理员（排障只读语义用） */
    public boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }
}
