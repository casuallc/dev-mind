package com.devmind.security.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.project.ProjectAccessChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CAP-66 权限判定聚合：项目成员判定经 {@code ObjectProvider<ProjectAccessChecker>} 探测
 * （CAP-62 未实施 = 缺席不过滤，落地后自动收紧）；ADMIN 判定走 IdentityService 当前用户角色。
 */
@Component
public class SecurityAccessGuard {

    private final ObjectProvider<ProjectAccessChecker> accessChecker;
    private final IdentityService identityService;

    public SecurityAccessGuard(ObjectProvider<ProjectAccessChecker> accessChecker,
                               IdentityService identityService) {
        this.accessChecker = accessChecker;
        this.identityService = identityService;
    }

    /** 项目可读（列表/详情/导出） */
    public void requireRead(String actor, String projectId) {
        ProjectAccessChecker checker = accessChecker.getIfAvailable();
        if (checker != null && projectId != null && !checker.canRead(actor, projectId)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "无项目 " + projectId + " 的读权限");
        }
    }

    /** 项目可写（发起/抑制/复核等操作面） */
    public void requireWrite(String actor, String projectId) {
        ProjectAccessChecker checker = accessChecker.getIfAvailable();
        if (checker != null && projectId != null && !checker.canWrite(actor, projectId)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "无项目 " + projectId + " 的写权限");
        }
    }

    public boolean isAdmin() {
        try {
            return identityService.currentUser()
                    .map(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 平台级操作（平台级 CVE 抑制、工具自检等）仅 ADMIN */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "仅管理员可执行该操作");
        }
    }
}
