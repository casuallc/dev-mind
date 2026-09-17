package com.devmind.knowledge.dto;

/**
 * CAP-45 飞书导入/重同步逐条结果。
 * status: created | updated | unchanged | failed；failed 时 error 带原因。
 */
public record FeishuImportResult(String url, String status, Long entryId, String error) {

    public static FeishuImportResult of(String url, String status, Long entryId) {
        return new FeishuImportResult(url, status, entryId, null);
    }

    public static FeishuImportResult failed(String url, String error) {
        return new FeishuImportResult(url, "failed", null, error);
    }
}
