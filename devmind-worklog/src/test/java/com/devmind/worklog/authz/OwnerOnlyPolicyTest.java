package com.devmind.worklog.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.ResourceTarget;
import com.devmind.common.authz.SubjectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-108 OWNER_ONLY 判定矩阵：本人放行；非本人一律 RESOURCE_HIDDEN（404 语义）；
 * ADMIN 不扩展（CAP-108 §4.2.2）；SYSTEM 受信任务放行；归属缺失 HIDDEN。
 */
class OwnerOnlyPolicyTest {

    private final OwnerOnlyPolicy policy = new OwnerOnlyPolicy();

    private static ResolvedSubject user(String username, String baseRole) {
        return new ResolvedSubject(SubjectType.USER, "id-" + username, username, baseRole, baseRole);
    }

    private static ResourceTarget ownedBy(String owner) {
        return ResourceTarget.owned("WORKLOG_ENTRY", "e1", owner);
    }

    @Test
    void 归属本人放行() {
        assertTrue(policy.evaluate(user("alice", "DEVELOPER"), null, ownedBy("alice")).allowed());
    }

    @Test
    void 非本人RESOURCE_HIDDEN() {
        AuthorizationDecision d = policy.evaluate(user("bob", "DEVELOPER"), null, ownedBy("alice"));
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, d.reasonCode());
    }

    @Test
    void ADMIN不扩展非本人对象() {
        AuthorizationDecision d = policy.evaluate(user("root", ResolvedSubject.BASE_ADMIN), null, ownedBy("alice"));
        assertEquals(ReasonCodes.RESOURCE_HIDDEN, d.reasonCode());
    }

    @Test
    void SYSTEM放行() {
        ResolvedSubject system = new ResolvedSubject(SubjectType.SYSTEM, "sched", "sched",
                ResolvedSubject.BASE_ADMIN, "SYSTEM");
        assertTrue(policy.evaluate(system, null, ownedBy("alice")).allowed());
    }

    @Test
    void 归属缺失RESOURCE_HIDDEN() {
        assertEquals(ReasonCodes.RESOURCE_HIDDEN,
                policy.evaluate(user("alice", "DEVELOPER"), null, ResourceTarget.owned("WORKLOG_ENTRY", "e1", null))
                        .reasonCode());
        assertEquals(ReasonCodes.RESOURCE_HIDDEN,
                policy.evaluate(user("alice", "DEVELOPER"), null, null).reasonCode());
    }
}
