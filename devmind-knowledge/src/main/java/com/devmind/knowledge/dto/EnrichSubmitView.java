package com.devmind.knowledge.dto;

/** CAP-85 FR-28：增强任务提交结果。 */
public record EnrichSubmitView(String taskId, Long kbId, Long entryId, int limit) {
}
