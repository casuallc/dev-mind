package com.devmind.knowledge.dto;

import java.time.Instant;

/** CAP-84 FR-02 上传受理结果：立即返回批次/任务，前端据此轮询进度。 */
public record ImportSubmitResult(String batchId, String taskId, int totalFiles) {
}
