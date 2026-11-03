package com.devmind.common.authz;

/**
 * CAP-108 实时解析后的有效主体：基础角色取 roles.base_role（当前有效值），roleCode 为可管理角色编码。
 * 由 auth 的 SubjectResolver 构造；资源策略只消费本对象，不反向查用户表。
 */
public record ResolvedSubject(
        SubjectType subjectType,
        String subjectId,
        String username,
        /** ADMIN / DEVELOPER / VIEWER（角色当前 base_role） */
        String baseRole,
        String roleCode) {

    public static final String BASE_ADMIN = "ADMIN";
    public static final String BASE_DEVELOPER = "DEVELOPER";
    public static final String BASE_VIEWER = "VIEWER";

    /** 内置 ADMIN 基础角色：业务角色/ACTION/项目 ACL 按平台运维语义绕过（凭证与确认约束仍执行）。 */
    public boolean admin() {
        return BASE_ADMIN.equals(baseRole);
    }

    public boolean system() {
        return subjectType == SubjectType.SYSTEM;
    }
}
