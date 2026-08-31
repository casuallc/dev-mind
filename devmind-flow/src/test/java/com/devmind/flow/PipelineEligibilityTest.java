package com.devmind.flow;

import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.WorkItemEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineEligibilityTest {

    private WorkItemView wi(String id, String type, String status) {
        return new WorkItemView(id, "p1", "r1", null, 1L, "WI-1", type, "t", "spec",
                status, null, null, "local", Instant.now(), Instant.now());
    }

    @Test
    void DONE的代码类工作单元可构建() {
        assertTrue(PipelineEligibility.buildable(WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_DONE));
        assertTrue(PipelineEligibility.buildable(WorkItemEntity.TYPE_TEST, WorkItemEntity.STATUS_DONE));
    }

    @Test
    void 非DONE或非代码类不可构建() {
        assertFalse(PipelineEligibility.buildable(WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_IN_PROGRESS));
        assertFalse(PipelineEligibility.buildable(WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_TODO));
        assertFalse(PipelineEligibility.buildable(WorkItemEntity.TYPE_DESIGN, WorkItemEntity.STATUS_DONE));
        assertFalse(PipelineEligibility.buildable(WorkItemEntity.TYPE_DOCUMENT, WorkItemEntity.STATUS_DONE));
        assertFalse(PipelineEligibility.buildable(WorkItemEntity.TYPE_REVIEW, WorkItemEntity.STATUS_DONE));
    }

    @Test
    void 已有构建记录的WI不重复触发() {
        List<WorkItemView> items = List.of(
                wi("a", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_DONE),
                wi("b", WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.STATUS_DONE),
                wi("c", WorkItemEntity.TYPE_DESIGN, WorkItemEntity.STATUS_DONE));
        List<WorkItemView> pending = PipelineEligibility.pendingBuilds(items, Set.of("a"));
        assertEquals(List.of("b"), pending.stream().map(WorkItemView::id).toList());
    }
}
