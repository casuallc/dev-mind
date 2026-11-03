package com.devmind.worklog.authz;

import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationEffect;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.OperationSpecProvider;
import com.devmind.common.authz.ResourcePolicies;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * CAP-108 M1 工作日志域业务操作目录（FR-04 首批映射）：工作条目创建。
 * 个人域：角色底线 ADMIN/DEVELOPER（VIEWER 只读不写日志），ACTION 复用现有
 * 「工作台-工作日志-管理条目」授权，资源策略 OWNER_ONLY（本人归属，不因 ADMIN 扩展）。
 */
@Component
public class WorklogOperationCatalog implements OperationSpecProvider {

    private static final Set<String> WRITE_ROLES = Set.of("ADMIN", "DEVELOPER");

    @Override
    public List<OperationSpec> operations() {
        return List.of(
                OperationSpec.of("worklog.entry.create", "worklog", OperationEffect.WRITE,
                        WRITE_ROLES, GrantRule.anyOf("top.worklog.action.manage-entry"),
                        ResourcePolicies.OWNER_ONLY, "WORKLOG_ENTRY"));
    }
}
