package com.devmind.project;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.auth.IdentityService;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.model.WorkItemEntity;
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
 * 验收联动（test.completed → 需求 ACCEPTANCE + 待验收广播）：
 * 回归全绿且工作单元全终态 → rollup 进 ACCEPTANCE 并发 requirement.acceptance.ready；
 * 失败事件 / 无 workItemId / 仍有活跃工作单元 / 已终态（DONE/CANCELLED）需求不动作。
 * 无 Spring 上下文，repository 用 JDK 动态代理内存 fake。
 */
class RequirementAcceptanceListenerTest {

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

    private final Map<String, RequirementEntity> reqStore = new HashMap<>();
    private final Map<String, WorkItemEntity> wiStore = new HashMap<>();
    private FakeEventPublisher events;
    private RequirementAcceptanceListener listener;

    @BeforeEach
    void setUp() {
        events = new FakeEventPublisher();
        RequirementRepository requirementRepo = proxy(RequirementRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "save" -> {
                        RequirementEntity e = (RequirementEntity) args[0];
                        reqStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(reqStore.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        WorkItemRepository workItemRepo = proxy(WorkItemRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findById" -> Optional.ofNullable(wiStore.get((String) args[0]));
                    case "findByRequirementIdOrderBySeqAsc" -> wiStore.values().stream()
                            .filter(w -> ((String) args[0]).equals(w.getRequirementId()))
                            .toList();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        InvocationHandler untouchable = (p, m, args) -> {
            throw new UnsupportedOperationException(m.getName());
        };
        RequirementService requirementService = new RequirementService(
                proxy(ProjectRepository.class, untouchable),
                requirementRepo,
                workItemRepo,
                proxy(DesignRepository.class, untouchable),
                proxy(RelationRepository.class, untouchable),
                new FakeIdentityService(),
                proxy(org.springframework.beans.factory.ObjectProvider.class,
                        (p, m, args) -> "getIfAvailable".equals(m.getName()) ? null : null),
                proxy(org.springframework.beans.factory.ObjectProvider.class,
                        (p, m, args) -> "getIfAvailable".equals(m.getName()) ? null : null),
                events);
        WorkItemService workItemService = new WorkItemService(requirementService, workItemRepo,
                null, null, null, events, new FakeIdentityService());
        listener = new RequirementAcceptanceListener(requirementService, workItemService, events);
    }

    private RequirementEntity requirement(String status) {
        RequirementEntity e = new RequirementEntity();
        e.setId("req1");
        e.setProjectId("proj1");
        e.setSeq(7L);
        e.setTitle("标题");
        e.setStatus(status);
        reqStore.put(e.getId(), e);
        return e;
    }

    private WorkItemEntity workItem(String status) {
        WorkItemEntity w = new WorkItemEntity();
        w.setId("wi" + (wiStore.size() + 1));
        w.setProjectId("proj1");
        w.setRequirementId("req1");
        w.setType(WorkItemEntity.TYPE_DEVELOPMENT);
        w.setStatus(status);
        wiStore.put(w.getId(), w);
        return w;
    }

    private static SimpleDomainEvent greenRun(String workItemId) {
        return SimpleDomainEvent.of("test.completed", "proj1", workItemId, "tester",
                "测试 #1 SUCCESS", "TEST_RUN", "1", true);
    }

    /** 回归全绿 + 工作单元全终态 → rollup 进 ACCEPTANCE 并广播待验收。 */
    @Test
    void greenRunMovesRequirementToAcceptanceAndBroadcasts() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS);
        workItem(WorkItemEntity.STATUS_DONE);

        listener.onTestCompleted(greenRun("wi1"));

        assertEquals(RequirementEntity.STATUS_ACCEPTANCE, e.getStatus());
        assertEquals(1, events.published.size());
        SimpleDomainEvent ev = (SimpleDomainEvent) events.published.get(0);
        assertEquals("requirement.acceptance.ready", ev.type());
        assertEquals("req1", ev.entityId());
        assertEquals("proj1", ev.projectId());
        assertTrue(ev.summary().contains("REQ-7"));
    }

    /** 仍有活跃工作单元 → rollup 保持 IN_PROGRESS，不广播（回归绿 ≠ 开发完结）。 */
    @Test
    void greenRunWithActiveWorkItemsDoesNothing() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS);
        workItem(WorkItemEntity.STATUS_IN_PROGRESS);

        listener.onTestCompleted(greenRun("wi1"));

        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, e.getStatus());
        assertTrue(events.published.isEmpty());
    }

    /** 失败测试事件不联动。 */
    @Test
    void failedRunIgnored() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS);
        workItem(WorkItemEntity.STATUS_DONE);

        listener.onTestCompleted(SimpleDomainEvent.of("test.completed", "proj1", "wi1", "tester",
                "测试 #1 FAILED", "TEST_RUN", "1", false));

        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, e.getStatus());
        assertTrue(events.published.isEmpty());
    }

    /** 无 workItemId 的项目级测试不联动。 */
    @Test
    void missingWorkItemIdIgnored() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS);
        workItem(WorkItemEntity.STATUS_DONE);

        listener.onTestCompleted(greenRun(null));

        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, e.getStatus());
        assertTrue(events.published.isEmpty());
    }

    /** 已 DONE 的需求不被回归事件顶回 ACCEPTANCE，也不重复广播。 */
    @Test
    void doneRequirementNotDisturbed() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_DONE);
        workItem(WorkItemEntity.STATUS_DONE);

        listener.onTestCompleted(greenRun("wi1"));

        assertEquals(RequirementEntity.STATUS_DONE, e.getStatus());
        assertTrue(events.published.isEmpty());
    }

    /** 已 ACCEPTANCE 的需求再次回归全绿 → 重复广播待验收（每次绿跑都是一次验收提醒）。 */
    @Test
    void alreadyAcceptanceGreenRunRebroadcasts() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_ACCEPTANCE);
        workItem(WorkItemEntity.STATUS_DONE);

        listener.onTestCompleted(greenRun("wi1"));

        assertEquals(RequirementEntity.STATUS_ACCEPTANCE, e.getStatus());
        assertEquals(1, events.published.size());
        assertEquals("requirement.acceptance.ready", events.published.get(0).type());
    }
}
