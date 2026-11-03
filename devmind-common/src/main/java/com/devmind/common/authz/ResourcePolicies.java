package com.devmind.common.authz;

/**
 * CAP-108 资源策略标识常量（OperationSpec.resourcePolicyId 引用）。
 */
public final class ResourcePolicies {

    /** 项目可读（PUBLIC 全员 / PRIVATE 成员+ADMIN / WORKLOG 仅归属+ADMIN） */
    public static final String PROJECT_READ = "PROJECT_READ";
    /** 项目可写（成员或 ADMIN） */
    public static final String PROJECT_WRITE = "PROJECT_WRITE";
    /** 本人归属（不因 ADMIN 扩展） */
    public static final String OWNER_ONLY = "OWNER_ONLY";

    private ResourcePolicies() {
    }
}
