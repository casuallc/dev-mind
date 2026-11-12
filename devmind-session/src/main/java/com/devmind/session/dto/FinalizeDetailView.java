package com.devmind.session.dto;

import java.time.Instant;

/**
 * 收口记录视图（GET /api/sessions/{id}/finalize-detail）：
 * 收口时 runner 回传的明细（逐库合并摘要 + 并入基线的提交清单 + 变更统计）与收口时间。
 * detail 为 null = 未收口过或老 runner 未回传。
 */
public record FinalizeDetailView(String workspaceState, String detail, Instant finalizedAt) {
}
