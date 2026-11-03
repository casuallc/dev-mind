package com.devmind.common.authz;

import java.util.List;
import java.util.Set;

/**
 * CAP-108 §4.2.2 业务操作目录项（唯一规则源）：HTTP/Tool/Open API 映射均引用 operationId，
 * 角色、ACTION 表达式与资源策略只在此维护。
 */
public record OperationSpec(
        String id,
        String ownerModule,
        int contractVersion,
        OperationEffect effect,
        Set<SubjectType> allowedSubjectTypes,
        /** 显式基础角色集合（ADMIN/DEVELOPER/VIEWER），保留 ADMIN-only 边界与 VIEWER 只读底线 */
        Set<String> allowedBaseRoles,
        GrantRule grantRule,
        /** 资源策略 id（{@link ResourcePolicy#policyId()}）；null = 无对象级资源条件 */
        String resourcePolicyId,
        String targetType,
        /** 组合操作及自动后续操作清单（无则空表） */
        List<String> declaredEffects) {

    public OperationSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("operationId 必填");
        }
        if (effect == null) {
            throw new IllegalArgumentException("effect 必填: " + id);
        }
        if (allowedSubjectTypes == null || allowedSubjectTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedSubjectTypes 必填: " + id);
        }
        allowedSubjectTypes = Set.copyOf(allowedSubjectTypes);
        if (allowedBaseRoles == null || allowedBaseRoles.isEmpty()) {
            throw new IllegalArgumentException("allowedBaseRoles 必填: " + id);
        }
        allowedBaseRoles = Set.copyOf(allowedBaseRoles);
        if (grantRule == null) {
            throw new IllegalArgumentException("grantRule 必填（NONE 须显式）: " + id);
        }
        declaredEffects = declaredEffects == null ? List.of() : List.copyOf(declaredEffects);
    }

    /** 常用简化构造：USER 主体、无组合副作用。 */
    public static OperationSpec of(String id, String ownerModule, OperationEffect effect,
                                   Set<String> allowedBaseRoles, GrantRule grantRule,
                                   String resourcePolicyId, String targetType) {
        return new OperationSpec(id, ownerModule, 1, effect,
                Set.of(SubjectType.USER, SubjectType.DELEGATED_USER),
                allowedBaseRoles, grantRule, resourcePolicyId, targetType, List.of());
    }
}
