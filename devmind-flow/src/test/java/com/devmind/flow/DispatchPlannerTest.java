package com.devmind.flow;

import com.devmind.project.dto.RelationView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RelationEntity;
import com.devmind.project.model.WorkItemEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchPlannerTest {

    private WorkItemView wi(String id, String status) {
        return wi(id, WorkItemEntity.TYPE_DEVELOPMENT, status);
    }

    private WorkItemView wi(String id, String type, String status) {
        return new WorkItemView(id, "p1", "r1", null, 1L, "WI-1", type, "t", "spec",
                status, null, null, "local", Instant.now(), Instant.now());
    }

    private RelationView dep(String fromId, String toId) {
        return new RelationView("rel-" + fromId + "-" + toId, "p1", "work_item", fromId,
                "work_item", toId, RelationEntity.TYPE_DEPENDS_ON, Instant.now());
    }

    @Test
    void 无依赖的TODO全部就绪() {
        List<WorkItemView> ready = DispatchPlanner.readyItems(List.of(
                wi("a", WorkItemEntity.STATUS_TODO),
                wi("b", WorkItemEntity.STATUS_TODO)), List.of());
        assertEquals(2, ready.size());
    }

    @Test
    void 依赖全部DONE才就绪() {
        List<WorkItemView> items = List.of(
                wi("a", WorkItemEntity.STATUS_DONE),
                wi("b", WorkItemEntity.STATUS_IN_PROGRESS),
                wi("c", WorkItemEntity.STATUS_TODO),
                wi("d", WorkItemEntity.STATUS_TODO));
        List<RelationView> edges = List.of(dep("c", "a"), dep("d", "a"), dep("d", "b"));
        List<WorkItemView> ready = DispatchPlanner.readyItems(items, edges);
        assertEquals(List.of("c"), ready.stream().map(WorkItemView::id).toList());
    }

    @Test
    void 非TODO不派发() {
        List<WorkItemView> ready = DispatchPlanner.readyItems(List.of(
                wi("a", WorkItemEntity.STATUS_IN_PROGRESS),
                wi("b", WorkItemEntity.STATUS_DONE),
                wi("c", WorkItemEntity.STATUS_BLOCKED),
                wi("d", WorkItemEntity.STATUS_CANCELLED)), List.of());
        assertTrue(ready.isEmpty());
    }

    @Test
    void DESIGN型不自动派发() {
        List<WorkItemView> ready = DispatchPlanner.readyItems(List.of(
                wi("a", WorkItemEntity.TYPE_DESIGN, WorkItemEntity.STATUS_TODO)), List.of());
        assertTrue(ready.isEmpty());
    }

    @Test
    void 依赖CANCELLED视为未就绪() {
        List<WorkItemView> ready = DispatchPlanner.readyItems(List.of(
                wi("a", WorkItemEntity.STATUS_CANCELLED),
                wi("b", WorkItemEntity.STATUS_TODO)), List.of(dep("b", "a")));
        assertTrue(ready.isEmpty());
    }

    @Test
    void 非depends_on边与非工作单元边不影响() {
        RelationView implementsEdge = new RelationView("r1", "p1", "work_item", "b",
                "design", "d1", RelationEntity.TYPE_IMPLEMENTS, Instant.now());
        List<WorkItemView> ready = DispatchPlanner.readyItems(List.of(
                wi("b", WorkItemEntity.STATUS_TODO)), List.of(implementsEdge));
        assertEquals(1, ready.size());
    }
}
