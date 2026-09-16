package com.devmind.worklog.dto;

import java.util.List;

/**
 * CAP-41：会话成稿同步结果（POST /api/worklog/reports/sync 及 exit 自动镜像共用）。
 *
 * @param mirrored 已落镜像的报告（如 "日报 2026-09-16"）
 * @param skipped  命中文件但未覆盖的报告（已确认为大，如 "日报 2026-09-16（已确认，未覆盖）"）
 */
public record WorklogSyncResult(List<String> mirrored, List<String> skipped) {
}
