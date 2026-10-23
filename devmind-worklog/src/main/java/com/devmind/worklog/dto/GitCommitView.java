package com.devmind.worklog.dto;

import java.time.Instant;

/**
 * git 扫描预览条目（不落库）。
 *
 * <p>CAP-41 生成质量：body（提交正文）与 filesChanged/insertions/deletions（改动规模）
 * 随扫描一并带出，供日报/周报 prompt 做主题归并；预览接口多返回字段对前端无破坏。</p>
 */
public record GitCommitView(Long repoId, String repoName, String sha,
                            String authorName, String authorEmail,
                            Instant committedAt, String subject, String body,
                            int filesChanged, int insertions, int deletions,
                            boolean alreadyImported) {}
