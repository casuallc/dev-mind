package com.devmind.knowledge.dto;

/** CAP-85 FR-27：手动同步提交结果（异步任务 id，前端轮询同步源状态）。 */
public record SyncSubmitView(String taskId, Long sourceId) {
}
