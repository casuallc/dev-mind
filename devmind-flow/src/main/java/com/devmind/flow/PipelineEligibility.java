package com.devmind.flow;

import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.WorkItemEntity;

import java.util.List;
import java.util.Set;

/**
 * 执行链就绪判定（CAP-17）：纯函数，可单测、可替换。
 * 可自动构建 = DONE 且代码类工作单元（DEVELOPMENT/TEST；DESIGN/DOCUMENT/REVIEW 不构建）。
 * 幂等：已有构建记录的 WI 不重复触发（返工重建由人工触发）。
 */
public final class PipelineEligibility {

    private static final Set<String> BUILDABLE_TYPES = Set.of(
            WorkItemEntity.TYPE_DEVELOPMENT, WorkItemEntity.TYPE_TEST);

    private PipelineEligibility() {
    }

    public static boolean buildable(String type, String status) {
        return WorkItemEntity.STATUS_DONE.equals(status) && BUILDABLE_TYPES.contains(type);
    }

    public static boolean buildable(WorkItemView w) {
        return buildable(w.type(), w.status());
    }

    /** 待补构建：buildable 且不在已有构建记录的 WI 集合内。 */
    public static List<WorkItemView> pendingBuilds(List<WorkItemView> workItems, Set<String> builtWorkItemIds) {
        return workItems.stream()
                .filter(PipelineEligibility::buildable)
                .filter(w -> !builtWorkItemIds.contains(w.id()))
                .toList();
    }
}
