package com.devmind.project;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.auth.IdentityService;
import com.devmind.project.event.RequirementTerminalEvent;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.DesignRepository;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RelationRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.project.repo.WorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-51 FR-06（2026-09-22 修订）：人工翻转到终态（DONE/CANCELLED）→ 发布
 * {@link RequirementTerminalEvent}（带 workspaceOwner，session 模块据此释放需求工作树 +
 * 删远端需求分支）；非终态翻转不发布。无 Spring 上下文，repository 用 JDK 动态代理内存 fake。
 */
class RequirementServiceTerminalEventTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class FakeIdentityService extends IdentityService {
        FakeIdentityService() { super(null, null, null); }
        @Override public String currentActor() { return "tester"; }
    }

    static class FakeEventPublisher extends DomainEventPublisher {
        final List<DomainEvent> published = new ArrayList<>();
        FakeEventPublisher() { super(null); }
        @Override public void publish(DomainEvent event) { published.add(event); }
    }

    private final Map<String, RequirementEntity> store = new HashMap<>();
    private FakeEventPublisher events;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        events = new FakeEventPublisher();
        RequirementRepository requirementRepo = proxy(RequirementRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "save" -> {
                        RequirementEntity e = (RequirementEntity) args[0];
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        // updateStatus 链路不触达的依赖：任一调用即失败，防误碰
        InvocationHandler untouchable = (p, m, args) -> {
            throw new UnsupportedOperationException(m.getName());
        };
        service = new RequirementService(
                proxy(ProjectRepository.class, untouchable),
                requirementRepo,
                proxy(WorkItemRepository.class, untouchable),
                proxy(DesignRepository.class, untouchable),
                proxy(RelationRepository.class, untouchable),
                new FakeIdentityService(),
                proxy(org.springframework.beans.factory.ObjectProvider.class,
                        (p, m, args) -> "getIfAvailable".equals(m.getName()) ? null : null),
                proxy(org.springframework.beans.factory.ObjectProvider.class,
                        (p, m, args) -> "getIfAvailable".equals(m.getName()) ? null : null),
                events);
    }

    private RequirementEntity requirement(String status, String workspaceOwner) {
        RequirementEntity e = new RequirementEntity();
        e.setId("req1");
        e.setProjectId("proj1");
        e.setSeq(7L);
        e.setTitle("标题");
        e.setStatus(status);
        e.setWorkspaceOwner(workspaceOwner);
        store.put(e.getId(), e);
        return e;
    }

    /** 验收 DONE → 发布终态事件（携带 workspaceOwner 与终态值）。 */
    @Test
    void donePublishesTerminalEvent() {
        requirement(RequirementEntity.STATUS_ACCEPTANCE, "u1");

        service.updateStatus("proj1", "req1", "DONE");

        assertEquals(1, events.published.size());
        RequirementTerminalEvent ev = (RequirementTerminalEvent) events.published.get(0);
        assertEquals("req1", ev.requirementId());
        assertEquals("proj1", ev.projectId());
        assertEquals("u1", ev.workspaceOwner(), "事件必须带 workspaceOwner（监听方不再反查需求行）");
        assertEquals("DONE", ev.status());
        assertEquals("tester", ev.actor());
    }

    /** 取消 CANCELLED → 同样发布终态事件。 */
    @Test
    void cancelledPublishesTerminalEvent() {
        requirement(RequirementEntity.STATUS_IN_PROGRESS, "u1");

        service.updateStatus("proj1", "req1", "CANCELLED");

        assertEquals(1, events.published.size());
        assertEquals("CANCELLED", ((RequirementTerminalEvent) events.published.get(0)).status());
    }

    /** 非终态推进（如 IN_PROGRESS）不发布事件。 */
    @Test
    void nonTerminalPublishesNothing() {
        requirement(RequirementEntity.STATUS_DRAFT, "u1");

        service.updateStatus("proj1", "req1", "IN_PROGRESS");

        assertTrue(events.published.isEmpty());
    }

    /** 无工作区的需求（workspaceOwner 空）也发布——监听方按 key 查不到会话会无操作返回。 */
    @Test
    void terminalWithoutWorkspaceStillPublishes() {
        requirement(RequirementEntity.STATUS_ACCEPTANCE, null);

        service.updateStatus("proj1", "req1", "DONE");

        assertEquals(1, events.published.size());
        assertEquals(null, ((RequirementTerminalEvent) events.published.get(0)).workspaceOwner());
    }
}
