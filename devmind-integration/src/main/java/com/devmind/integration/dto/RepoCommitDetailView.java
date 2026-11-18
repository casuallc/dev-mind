package com.devmind.integration.dto;

import java.util.List;

/**
 * CAP-111 FR-04 提交详情。time 直接透传 git --date=format 的「yyyy-MM-dd HH:mm:ss」字符串；
 * body 为提交正文（subject 之后部分，可为空串）；files 为变更文件清单
 * （merge 提交按第一父 diff，无变更时为空列表）。
 */
public record RepoCommitDetailView(String sha, String authorName, String authorEmail,
                                   String time, String subject, String body,
                                   List<FileChange> files) {

    /** 变更文件：status 为 git name-status 原始值（A/M/D/R100/C…），oldPath 仅改名/复制有值。 */
    public record FileChange(String status, String path, String oldPath) {
    }
}
