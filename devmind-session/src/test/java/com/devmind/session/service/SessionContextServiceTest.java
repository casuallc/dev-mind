package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.knowledge.KnowledgeInjector;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.repo.SessionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SessionContextService}：装配/manifest 摘要/缓存命中/DB 重建（无 Spring，手工 fake）。 */
class SessionContextServiceTest {

    private static final KnowledgeInjector TWO_ENTRIES =
            (project, taskSpec) -> new KnowledgeInjector.InjectionPackage("## 通用经验", "{}", 2);
    private static final KnowledgeInjector NO_HIT = (project, taskSpec) -> null;

    @Test
    void prepareReturnsManifestAndCachesPackage() {
        SessionContextService svc = new SessionContextService(TWO_ENTRIES, repoWith(null), null);
        ContextManifest m = svc.prepare("s1", null, "task");
        assertEquals(2, m.entries());

        Optional<ContextPackage> pkg = svc.find("s1");
        assertTrue(pkg.isPresent());
        assertEquals("## 通用经验", pkg.get().claudeMd());
        // manifest 摘要必须与包序列化字节流一致（runner 据此校验）
        assertEquals(m.sha256(), ContextPackages.sha256Hex(ContextPackages.toJsonBytes(pkg.get())));
        assertEquals(m.totalBytes(), ContextPackages.toJsonBytes(pkg.get()).length);
    }

    @Test
    void prepareNullWhenNoHit() {
        SessionContextService svc = new SessionContextService(NO_HIT, repoWith(null), null);
        assertNull(svc.prepare("s1", null, "task"));
        assertTrue(svc.find("unknown").isEmpty());
    }

    @Test
    void rebuildsFromDbWhenCacheMiss() {
        SessionEntity ent = new SessionEntity();
        ent.setId("s2");
        ent.setTaskSpec("task");
        SessionContextService svc = new SessionContextService(TWO_ENTRIES, repoWith(ent), null);
        Optional<ContextPackage> pkg = svc.find("s2");
        assertTrue(pkg.isPresent());
        assertEquals("## 通用经验", pkg.get().claudeMd());
    }

    /** findById 返回给定实体的 SessionRepository 代理（其余方法不支持）。 */
    private static SessionRepository repoWith(SessionEntity ent) {
        return (SessionRepository) Proxy.newProxyInstance(
                SessionContextServiceTest.class.getClassLoader(),
                new Class<?>[]{SessionRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(ent);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
