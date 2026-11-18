package com.devmind.integration.service;

import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.event.RequirementDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-27：需求删除事件 → 清掉该需求（internalType=REQUIREMENT）的全部外部关联。
 * 覆盖：只删该需求该类型的行（其他需求/其他内部类型不受影响）；清理失败只记日志不抛
 * （不能让删除本身回滚）。
 */
class RequirementExternalLinkCleanupTest {

    private final List<ExternalLinkEntity> store = new ArrayList<>();
    private boolean deleteFails;
    private RequirementExternalLinkCleanup cleanup;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    @BeforeEach
    void setUp() {
        ExternalLinkRepository linkRepo = proxy(ExternalLinkRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "deleteByInternalTypeAndInternalId" -> {
                        if (deleteFails) {
                            throw new RuntimeException("数据库抖动");
                        }
                        store.removeIf(l -> args[0].equals(l.getInternalType()) && args[1].equals(l.getInternalId()));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        cleanup = new RequirementExternalLinkCleanup(linkRepo);
    }

    private ExternalLinkEntity link(String internalType, String internalId, String externalType, String key) {
        ExternalLinkEntity l = new ExternalLinkEntity();
        l.setInternalType(internalType);
        l.setInternalId(internalId);
        l.setExternalType(externalType);
        l.setExternalKey(key);
        store.add(l);
        return l;
    }

    @Test
    void 删除事件清掉该需求全部外部关联且不误伤其他行() {
        link(ExternalLinkEntity.INTERNAL_REQUIREMENT, "req-1", ExternalLinkEntity.EXTERNAL_ISSUE, "PROJ-1");
        link(ExternalLinkEntity.INTERNAL_REQUIREMENT, "req-1", "MR", "!12");
        // 其他需求的 link、以及同 id 但其他内部类型的 link 都必须保留
        link(ExternalLinkEntity.INTERNAL_REQUIREMENT, "req-2", ExternalLinkEntity.EXTERNAL_ISSUE, "PROJ-2");
        link("WORK_ITEM", "req-1", ExternalLinkEntity.EXTERNAL_ISSUE, "PROJ-9");

        cleanup.onRequirementDeleted(new RequirementDeletedEvent("req-1", "p1", null, "tester"));

        assertEquals(2, store.size());
        assertTrue(store.stream().noneMatch(l ->
                ExternalLinkEntity.INTERNAL_REQUIREMENT.equals(l.getInternalType()) && "req-1".equals(l.getInternalId())));
        assertTrue(store.stream().anyMatch(l -> "req-2".equals(l.getInternalId())));
        assertTrue(store.stream().anyMatch(l -> "WORK_ITEM".equals(l.getInternalType())));
    }

    @Test
    void 清理失败只记日志不抛出() {
        deleteFails = true;

        assertDoesNotThrow(() ->
                cleanup.onRequirementDeleted(new RequirementDeletedEvent("req-1", "p1", null, "tester")));
    }
}
