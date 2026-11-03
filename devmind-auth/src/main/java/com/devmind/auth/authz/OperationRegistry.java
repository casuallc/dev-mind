package com.devmind.auth.authz;

import com.devmind.auth.menu.MenuCatalog;
import com.devmind.auth.model.MenuItemEntity;
import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.OperationSpecProvider;
import com.devmind.common.authz.ResourcePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAP-108 FR-01 操作目录（唯一规则源）：汇总各模块 {@link OperationSpecProvider} 的声明并启动校验——
 * 未知 operationId 拒绝、重复 id 失败、ACTION key 必须存在于权限目录、资源策略 provider 必须就绪。
 * 缺策略不得放宽为仅 RBAC（§3 否决方案）。
 */
@Component
public class OperationRegistry {

    private static final Logger log = LoggerFactory.getLogger(OperationRegistry.class);

    private final Map<String, OperationSpec> specs;
    private final Map<String, ResourcePolicy> policies;

    public OperationRegistry(ObjectProvider<OperationSpecProvider> specProviders,
                             ObjectProvider<ResourcePolicy> policyBeans) {
        Map<String, ResourcePolicy> policyMap = new LinkedHashMap<>();
        for (ResourcePolicy policy : policyBeans) {
            ResourcePolicy clash = policyMap.putIfAbsent(policy.policyId(), policy);
            if (clash != null) {
                throw new IllegalStateException("资源策略 id 重复: " + policy.policyId()
                        + "（" + clash.getClass().getName() + " / " + policy.getClass().getName() + "）");
            }
        }
        Set<String> actionKeys = MenuCatalog.items().stream()
                .filter(item -> MenuItemEntity.TYPE_ACTION.equals(item.nodeType()))
                .map(MenuCatalog.Item::menuKey)
                .collect(Collectors.toSet());

        Map<String, OperationSpec> specMap = new LinkedHashMap<>();
        for (OperationSpecProvider provider : specProviders) {
            for (OperationSpec spec : provider.operations()) {
                OperationSpec clash = specMap.putIfAbsent(spec.id(), spec);
                if (clash != null) {
                    throw new IllegalStateException("operationId 重复登记: " + spec.id()
                            + "（" + clash.ownerModule() + " / " + spec.ownerModule() + "）");
                }
                validate(spec, actionKeys, policyMap);
            }
        }
        this.specs = Map.copyOf(specMap);
        this.policies = Map.copyOf(policyMap);
        log.info("CAP-108 操作目录装配完成：{} 个操作，{} 个资源策略", specs.size(), policies.size());
    }

    private void validate(OperationSpec spec, Set<String> actionKeys, Map<String, ResourcePolicy> policyMap) {
        if (spec.grantRule().kind() != GrantRule.Kind.NONE) {
            for (String key : spec.grantRule().keys()) {
                if (!actionKeys.contains(key)) {
                    throw new IllegalStateException(
                            "操作 " + spec.id() + " 引用未知 ACTION 权限编码: " + key);
                }
            }
        }
        if (spec.resourcePolicyId() != null && !policyMap.containsKey(spec.resourcePolicyId())) {
            throw new IllegalStateException("操作 " + spec.id()
                    + " 的资源策略未提供: " + spec.resourcePolicyId());
        }
    }

    /** @return null = 未登记（引擎归一为 OPERATION_UNKNOWN） */
    public OperationSpec find(String operationId) {
        return operationId == null ? null : specs.get(operationId);
    }

    /** @return null = 策略缺失（引擎归一为 POLICY_UNAVAILABLE，不得放行） */
    public ResourcePolicy policy(String policyId) {
        return policyId == null ? null : policies.get(policyId);
    }

    public int size() {
        return specs.size();
    }
}
