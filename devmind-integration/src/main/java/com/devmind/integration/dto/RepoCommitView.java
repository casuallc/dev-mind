package com.devmind.integration.dto;

/**
 * CAP-109 FR-04 代码库提交历史行。time 直接透传 git log --date=format 的
 * 「yyyy-MM-dd HH:mm:ss」字符串（与全局 JacksonConfig 时间格式同口径，不做二次序列化）。
 */
public record RepoCommitView(String sha, String authorName, String authorEmail,
                             String time, String subject) {
}
