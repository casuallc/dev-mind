package com.devmind.auth.authz;

import com.devmind.common.authz.AuthorizationDecision;
import com.devmind.common.authz.GrantRule;
import com.devmind.common.authz.OperationEffect;
import com.devmind.common.authz.OperationSpec;
import com.devmind.common.authz.OperationSpecProvider;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.ResourcePolicy;
import com.devmind.common.authz.ResourceTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationRegistryTest {

    /** 极简 ObjectProvider：仅支撑本类的构造迭代与 getIfAvailable。 */
    static <T> ObjectProvider<T> providerOf(List<T> items) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return items.isEmpty() ? null : items.get(0);
            }

            @Override
            public T getIfAvailable() {
                return items.isEmpty() ? null : items.get(0);
            }

            @Override
            public Iterator<T> iterator() {
                return items.iterator();
            }
        };
    }

    private static ResourcePolicy stubPolicy(String id) {
        return new ResourcePolicy() {
            @Override
            public String policyId() {
                return id;
            }

            @Override
            public AuthorizationDecision evaluate(ResolvedSubject s, OperationSpec op, ResourceTarget t) {
                return AuthorizationDecision.allow();
            }
        };
    }

    private static OperationSpecProvider specs(OperationSpec... specs) {
        return () -> List.of(specs);
    }

    private static OperationSpec readOp(String id) {
        return OperationSpec.of(id, "test", OperationEffect.READ,
                Set.of("ADMIN", "DEVELOPER", "VIEWER"), GrantRule.none(), null, "PROJECT");
    }

    @Test
    void 重复operationId启动失败() {
        assertThrows(IllegalStateException.class, () -> new OperationRegistry(
                providerOf(List.of(specs(readOp("a.b"), readOp("a.b")))),
                providerOf(List.of())));
    }

    @Test
    void 未知ACTION权限编码启动失败() {
        OperationSpec bad = OperationSpec.of("x.y", "test", OperationEffect.WRITE,
                Set.of("ADMIN"), GrantRule.anyOf("no.such.action"), null, "PROJECT");
        assertThrows(IllegalStateException.class, () -> new OperationRegistry(
                providerOf(List.of(specs(bad))), providerOf(List.of())));
    }

    @Test
    void 资源策略缺失启动失败() {
        OperationSpec bad = OperationSpec.of("x.y", "test", OperationEffect.READ,
                Set.of("ADMIN"), GrantRule.none(), "MISSING_POLICY", "PROJECT");
        assertThrows(IllegalStateException.class, () -> new OperationRegistry(
                providerOf(List.of(specs(bad))), providerOf(List.of())));
    }

    @Test
    void 合法目录装配成功且策略可查() {
        OperationSpec ok = OperationSpec.of("x.y", "test", OperationEffect.READ,
                Set.of("ADMIN"), GrantRule.anyOf("project.requirements.action.create"),
                "PROJECT_READ", "PROJECT");
        OperationRegistry registry = assertDoesNotThrow(() -> new OperationRegistry(
                providerOf(List.of(specs(ok))), providerOf(List.of(stubPolicy("PROJECT_READ")))));
        assertNotNull(registry.find("x.y"));
        assertNotNull(registry.policy("PROJECT_READ"));
    }

    @Test
    void 资源策略id重复启动失败() {
        assertThrows(IllegalStateException.class, () -> new OperationRegistry(
                providerOf(List.of()),
                providerOf(List.of(stubPolicy("P"), stubPolicy("P")))));
    }
}
