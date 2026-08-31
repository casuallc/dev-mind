package com.devmind.flow;

import com.devmind.project.dto.RelationView;
import com.devmind.project.dto.WorkItemView;
import com.devmind.project.model.RelationEntity;
import com.devmind.project.model.WorkItemEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖就绪判定（CAP-15）：纯函数，可单测、可替换。
 * 就绪 = TODO 且非 DESIGN 型（方案由 CAP-14 流程动作人工发起）且所有 depends_on 目标均 DONE。
 * 依赖目标 CANCELLED 或不在本需求清单内一律视为未就绪，等人介入。
 */
public final class DispatchPlanner {

    private DispatchPlanner() {
    }

    public static List<WorkItemView> readyItems(List<WorkItemView> workItems, List<RelationView> edges) {
        Map<String, String> statusById = new HashMap<>();
        for (WorkItemView w : workItems) {
            statusById.put(w.id(), w.status());
        }
        Set<String> blocked = new HashSet<>();
        for (RelationView e : edges) {
            if (!"work_item".equals(e.fromType()) || !RelationEntity.TYPE_DEPENDS_ON.equals(e.relationType())) {
                continue;
            }
            if (!WorkItemEntity.STATUS_DONE.equals(statusById.get(e.toId()))) {
                blocked.add(e.fromId());
            }
        }
        return workItems.stream()
                .filter(w -> WorkItemEntity.STATUS_TODO.equals(w.status()))
                .filter(w -> !WorkItemEntity.TYPE_DESIGN.equals(w.type()))
                .filter(w -> !blocked.contains(w.id()))
                .toList();
    }
}
