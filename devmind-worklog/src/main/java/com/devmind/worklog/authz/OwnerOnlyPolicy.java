package com.devmind.worklog.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.ResourcePolicies;
import com.devmind.common.authz.ResourcePolicy;
import com.devmind.common.authz.ResourceTarget;
import org.springframework.stereotype.Component;

/**
 * CAP-108 §4.2.3 OWNER_ONLY 策略：个人域对象仅归属本人可操作。
 * 不因 ADMIN 扩展写入对象（CAP-108 §4.2.2：代办须另有具名管理操作）；
 * SYSTEM 受信任务放行（如调度器按 username 显式归属写入）。
 * 非归属对象一律 RESOURCE_HIDDEN（对外 404，不暴露存在性，沿用条目 CRUD 语义）。
 */
@Component
public class OwnerOnlyPolicy implements ResourcePolicy {

    @Override
    public String policyId() {
        return ResourcePolicies.OWNER_ONLY;
    }

    @Override
    public AuthorizationDecision evaluate(ResolvedSubject subject, OperationSpec operation, ResourceTarget target) {
        if (target == null || target.ownerId() == null || target.ownerId().isBlank()) {
            return AuthorizationDecision.deny(ReasonCodes.RESOURCE_HIDDEN, "资源目标缺少归属主体");
        }
        if (subject.system()) {
            return AuthorizationDecision.allow();
        }
        if (target.ownerId().equals(subject.username())) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny(ReasonCodes.RESOURCE_HIDDEN, "非本人归属对象");
    }
}
