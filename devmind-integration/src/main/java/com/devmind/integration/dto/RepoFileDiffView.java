package com.devmind.integration.dto;

/**
 * CAP-113 FR-01 单文件 diff 视图。patch 为 unified diff 原文（含 diff --git/index/---/+++
 * 头与 @@ hunk，超 2000 行截断 truncated=true）；additions/deletions 取自 numstat，
 * 二进制文件为 null 且 binary=true（patch 此时通常只有 "Binary files differ" 提示行）。
 * oldPath/status 前端从变更清单 FileChange 已知，不重复返回。
 */
public record RepoFileDiffView(String path, Integer additions, Integer deletions,
                               boolean binary, boolean truncated, String patch) {
}
