package com.devmind.project.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.ResourcePolicies;
import com.devmind.common.authz.ResourcePolicy;
import com.devmind.common.authz.ResourceTarget;
import org.springframework.stereotype.Component;

/** CAP-108 PROJECT_READ 策略：项目级可见性判定（委托 {@link ProjectAccess}）。 */
@Component
public class ProjectReadPolicy implements ResourcePolicy {

    private final ProjectAccess access;

    public ProjectReadPolicy(ProjectAccess access) {
        this.access = access;
    }

    @Override
    public String policyId() {
        return ResourcePolicies.PROJECT_READ;
    }

    @Override
    public AuthorizationDecision evaluate(ResolvedSubject subject, OperationSpec operation, ResourceTarget target) {
        if (target == null || target.projectId() == null) {
            return AuthorizationDecision.deny(ReasonCodes.RESOURCE_HIDDEN, "资源目标缺少项目归属");
        }
        return access.canRead(subject, target.projectId());
    }
}
