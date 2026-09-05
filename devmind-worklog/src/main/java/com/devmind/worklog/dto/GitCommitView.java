package com.devmind.worklog.dto;

import java.time.Instant;

/** git 扫描预览条目（不落库）。 */
public record GitCommitView(Long repoId, String repoName, String sha,
                            String authorName, String authorEmail,
                            Instant committedAt, String subject,
                            boolean alreadyImported) {}
