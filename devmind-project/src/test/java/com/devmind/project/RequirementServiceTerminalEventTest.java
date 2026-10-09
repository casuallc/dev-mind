package com.devmind.project;

import com.devmind.common.event.DomainEvent;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.common.exception.DevMindException;
import com.devmind.auth.IdentityService;
import com.devmind.project.event.RequirementTerminalEvent;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final List<WorkItemEntity> workItems = new ArrayList<>();
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
        WorkItemRepository workItemRepo = proxy(WorkItemRepository.class, (p, m, args) ->
                switch (m.getName()) {
                    case "findByRequirementIdOrderBySeqAsc" -> workItems.stream()
                            .filter(w -> ((String) args[0]).equals(w.getRequirementId()))
                            .toList();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        // updateStatus 链路不触达的依赖：任一调用即失败，防误碰
        InvocationHandler untouchable = (p, m, args) -> {
            throw new UnsupportedOperationException(m.getName());
        };
        service = new RequirementService(
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

    private WorkItemEntity workItem(String status) {
        WorkItemEntity w = new WorkItemEntity();
        w.setId("wi" + (workItems.size() + 1));
        w.setRequirementId("req1");
        w.setType(WorkItemEntity.TYPE_DEVELOPMENT);
        w.setStatus(status);
        workItems.add(w);
        return w;
    }

    /** DONE 前置检查：存在未完结工作单元 → 409 拒绝，状态不变、不发终态事件。 */
    @Test
    void doneWithActiveWorkItemRejected() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS, null);
        workItem(WorkItemEntity.STATUS_IN_PROGRESS);

        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.updateStatus("proj1", "req1", "DONE"));

        assertTrue(ex.getMessage().contains("未完结工作单元"));
        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, e.getStatus());
        assertTrue(events.published.isEmpty());
    }

    /** force=true 跳过 DONE 前置检查（前端二次确认后强制完成）。 */
    @Test
    void doneWithForceBypassesPrecondition() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_IN_PROGRESS, null);
        workItem(WorkItemEntity.STATUS_IN_PROGRESS);

        service.updateStatus("proj1", "req1", "DONE", true);

        assertEquals(RequirementEntity.STATUS_DONE, e.getStatus());
        assertEquals(1, events.published.size());
    }

    /** 工作单元全终态时 DONE 不受前置检查影响。 */
    @Test
    void doneWithAllTerminalWorkItemsPasses() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_ACCEPTANCE, null);
        workItem(WorkItemEntity.STATUS_DONE);
        workItem(WorkItemEntity.STATUS_CANCELLED);

        service.updateStatus("proj1", "req1", "DONE");

        assertEquals(RequirementEntity.STATUS_DONE, e.getStatus());
    }

    /** rollup 回滚：人工 DONE 后工作单元重新活跃 → 顶回 IN_PROGRESS。 */
    @Test
    void recomputeRollsBackDoneWhenWorkItemReactivated() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_DONE, null);
        workItem(WorkItemEntity.STATUS_IN_PROGRESS);

        service.recomputeStatus("req1");

        assertEquals(RequirementEntity.STATUS_IN_PROGRESS, e.getStatus());
    }

    /** rollup 保持：人工 DONE 且工作单元全终态 → 不回退 ACCEPTANCE。 */
    @Test
    void recomputeKeepsDoneWhenAllTerminal() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_DONE, null);
        workItem(WorkItemEntity.STATUS_DONE);

        service.recomputeStatus("req1");

        assertEquals(RequirementEntity.STATUS_DONE, e.getStatus());
    }

    /** rollup 保持：CANCELLED 永不回滚（即使工作单元重新活跃）。 */
    @Test
    void recomputeNeverRollsBackCancelled() {
        RequirementEntity e = requirement(RequirementEntity.STATUS_CANCELLED, null);
        workItem(WorkItemEntity.STATUS_IN_PROGRESS);

        service.recomputeStatus("req1");

        assertEquals(RequirementEntity.STATUS_CANCELLED, e.getStatus());
    }
}
