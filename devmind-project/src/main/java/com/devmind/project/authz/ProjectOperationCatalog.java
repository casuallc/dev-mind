package com.devmind.project.authz;

import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationEffect;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.OperationSpecProvider;
import com.devmind.common.authz.ResourcePolicies;
import com.devmind.common.authz.SubjectType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * CAP-108 M1 项目域业务操作目录（FR-04 首批映射）：
 * 项目列表/详情、需求读取/创建、需求草稿生成、工作单元创建。
 * 角色底线：READ 全角色；WRITE/GENERATE 仅 ADMIN/DEVELOPER（VIEWER 只读）。
 */
@Component
public class ProjectOperationCatalog implements OperationSpecProvider {

    private static final Set<String> READ_ROLES = Set.of("ADMIN", "DEVELOPER", "VIEWER");
    private static final Set<String> WRITE_ROLES = Set.of("ADMIN", "DEVELOPER");
    /** M1 兼容：开放 API 历史 apikey 主体（SERVICE）可读项目列表/详情 */
    private static final Set<SubjectType> READ_TYPES =
            Set.of(SubjectType.USER, SubjectType.DELEGATED_USER, SubjectType.SERVICE);
    private static final Set<SubjectType> WRITE_TYPES = Set.of(SubjectType.USER, SubjectType.DELEGATED_USER);

    @Override
    public List<OperationSpec> operations() {
        return List.of(
                // project.list 无对象级策略——可见性由 ProjectQueryScope 在查询层过滤
                new OperationSpec("project.list", "project", 1, OperationEffect.READ,
                        READ_TYPES, READ_ROLES, GrantRule.none(), null, "PROJECT", List.of()),
                new OperationSpec("project.read", "project", 1, OperationEffect.READ,
                        READ_TYPES, READ_ROLES, GrantRule.none(),
                        ResourcePolicies.PROJECT_READ, "PROJECT", List.of()),
                OperationSpec.of("requirement.read", "project", OperationEffect.READ,
                        READ_ROLES, GrantRule.none(), ResourcePolicies.PROJECT_READ, "REQUIREMENT"),
                OperationSpec.of("requirement.draft.generate", "project", OperationEffect.GENERATE,
                        WRITE_ROLES, GrantRule.anyOf("project.requirements.action.generate-draft"),
                        ResourcePolicies.PROJECT_READ, "PROJECT"),
                OperationSpec.of("requirement.create", "project", OperationEffect.WRITE,
                        WRITE_ROLES, GrantRule.anyOf("project.requirements.action.create"),
                        ResourcePolicies.PROJECT_WRITE, "PROJECT"),
                OperationSpec.of("work_item.create", "project", OperationEffect.WRITE,
                        WRITE_ROLES, GrantRule.anyOf("project.requirements.action.manage-workitem"),
                        ResourcePolicies.PROJECT_WRITE, "WORK_ITEM"));
    }
}
