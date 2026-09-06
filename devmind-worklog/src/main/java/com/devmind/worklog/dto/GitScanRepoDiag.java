package com.devmind.worklog.dto;

/**
 * 单仓库扫描诊断（不落库）：让前端能回答「为什么我勾选的仓库没有提交出现」。
 *
 * @param outcome      SCANNED（正常扫描，commitCount 可能为 0）/ SKIPPED（未参与扫描）/ FAILED（扫描出错）
 * @param authorFilter 实际使用的 --author 过滤串；null = 未过滤
 * @param detail       人读原因（跳过/失败原因、署名解析来源等）；null = 无补充
 */
public record GitScanRepoDiag(Long repoId, String repoName, String outcome,
                              String authorFilter, String detail, int commitCount) {}
