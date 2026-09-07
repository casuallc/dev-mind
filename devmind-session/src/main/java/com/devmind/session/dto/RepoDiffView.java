package com.devmind.session.dto;

import java.util.List;

/**
 * CAP-31 单仓库 diff 摘要（GET /api/sessions/{id}/diff 按库返回列表）。
 * 单库失败只填 error，不拖垮整组。
 *
 * @param repoName   仓库名（session_repos 快照）
 * @param primary    是否主库
 * @param stat       git diff --stat 输出（含未跟踪新文件行）
 * @param files      变更文件清单（未跟踪文件带 "?? " 前缀）
 * @param hasChanges 是否有变更
 * @param error      该库 diff 失败原因（已脱敏）；null = 正常
 */
public record RepoDiffView(String repoName, boolean primary, String stat, List<String> files,
                           boolean hasChanges, String error) {

    public static RepoDiffView error(String repoName, boolean primary, String error) {
        return new RepoDiffView(repoName, primary, "", List.of(), false, error);
    }

    public static RepoDiffView of(String repoName, boolean primary, String stat, List<String> files) {
        return new RepoDiffView(repoName, primary, stat, files, files != null && !files.isEmpty(), null);
    }
}
