package com.devmind.knowledge.dto;

import java.util.List;

/**
 * CAP-84 FR-24：方案沉淀请求。
 *
 * @param turnSeq       指定轮次（user 事件 seq）；空 = 最近一轮
 * @param title         文档标题；空 = 「会话标题 + 方案」
 * @param kind          CAP-03 文档类型 design（默认）/ report
 * @param projectId     可选归属项目
 * @param requirementId 可选关联需求
 * @param tags          可选标签（默认「知识库方案」）
 */
public record SolutionSaveRequest(Long turnSeq, String title, String kind, String projectId,
                                  String requirementId, List<String> tags) {
}
