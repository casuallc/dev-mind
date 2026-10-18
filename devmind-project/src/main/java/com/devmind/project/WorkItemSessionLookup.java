package com.devmind.project;

import java.time.Instant;
import java.util.List;

/**
 * 工作项关联会话查找端口（CAP-76 `get_work_item` 展示关联会话）：
 * project → session 反向依赖解耦，session 模块提供实现；缺席时工具不展示会话段。
 */
public interface WorkItemSessionLookup {

    List<SessionBrief> sessionsFor(String workItemId);

    record SessionBrief(String id, String status, String taskSpec, Instant createdAt) {
    }
}
