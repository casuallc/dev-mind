package com.devmind.usage.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;

/**
 * 用量数据的可见范围口径（CAP-62）：非 ADMIN 忽略 userId 入参强制本人；ADMIN 缺省看全部。
 * UsageStatsService / UsageRecordQueryService 共用，防两处口径漂移。
 */
final class UsageVisibility {

    private UsageVisibility() {
    }

    /** 非 ADMIN 忽略入参强制本人；ADMIN 传值按值、缺省看全部（返回 null）。 */
    static String resolveUser(IdentityService identityService, String requested) {
        if (!isAdmin(identityService)) {
            return identityService.currentActor();
        }
        return requested == null || requested.isBlank() ? null : requested;
    }

    static boolean isAdmin(IdentityService identityService) {
        return identityService.currentUser()
                .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                .orElse(false);
    }
}
