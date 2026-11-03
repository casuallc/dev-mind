package com.devmind.copilot.service;

import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.SubjectType;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-108 FR-06 确认凭据校验矩阵：缺凭据/不存在/属主不符/非执行链路/过期一律拒绝；
 * 仅 EXECUTING（CAP-76 原子抢占后的在途状态）+ 属主匹配 + 未过期放行。
 */
class CopilotConfirmationVerifierTest {

    private final Map<String, CopilotActionEntity> store = new HashMap<>();
    private final CopilotActionRepository repo = (CopilotActionRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{CopilotActionRepository.class},
            (p, m, args) -> switch (m.getName()) {
                case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final CopilotConfirmationVerifier verifier = new CopilotConfirmationVerifier(repo);

    private static ExecutionContext ctx(String actor, String actionId) {
        return ExecutionContext.builder(SubjectType.USER, actor, ExecutionSource.BOT)
                .subjectId(actor).actionId(actionId).build();
    }

    private CopilotActionEntity action(String id, String actor, String status) {
        CopilotActionEntity a = new CopilotActionEntity();
        a.setId(id);
        a.setActor(actor);
        a.setStatus(status);
        a.setExpiresAt(Instant.now().plusSeconds(300));
        store.put(id, a);
        return a;
    }

    @Test
    void 缺少确认凭据拒绝() {
        assertNotNull(verifier.verify(ctx("alice", null), null, null));
        assertNotNull(verifier.verify(ctx("alice", "  "), null, null));
    }

    @Test
    void 凭据不存在拒绝() {
        assertTrue(verifier.verify(ctx("alice", "ghost"), null, null).contains("不存在"));
    }

    @Test
    void 属主不匹配拒绝() {
        action("a1", "alice", CopilotActionEntity.EXECUTING);
        assertNotNull(verifier.verify(ctx("bob", "a1"), null, null));
    }

    @Test
    void 非执行链路状态拒绝_防伪造与复用() {
        action("p1", "alice", CopilotActionEntity.PENDING);
        action("s1", "alice", CopilotActionEntity.SUCCEEDED);
        assertNotNull(verifier.verify(ctx("alice", "p1"), null, null));
        assertNotNull(verifier.verify(ctx("alice", "s1"), null, null));
    }

    @Test
    void 过期拒绝() {
        CopilotActionEntity a = action("e1", "alice", CopilotActionEntity.EXECUTING);
        a.setExpiresAt(Instant.now().minusSeconds(1));
        assertNotNull(verifier.verify(ctx("alice", "e1"), null, null));
    }

    @Test
    void 执行链路上且属主匹配放行() {
        action("ok1", "alice", CopilotActionEntity.EXECUTING);
        assertNull(verifier.verify(ctx("alice", "ok1"), null, null));
    }
}
