package com.devmind.worklog.dto;

import java.util.List;

/**
 * CAP-41：会话成稿同步结果（POST /api/worklog/reports/sync 及 exit 自动镜像共用）。
 *
 * @param mirrored 实际写仓产生新 commit 的报告（如 "日报 2026-09-16"；内容一致的旧稿不计入）
 * @param skipped  被手改保护拦下的报告（如 "日报 2026-09-16（仓内已被手动修改，未覆盖）"；
 *                 2026-10-19 起由 mirrorAll 手改保护填充，场景生成路径恒空）
 */
public record WorklogSyncResult(List<String> mirrored, List<String> skipped) {
}
