package com.devmind.integration.dto;

import java.time.Instant;
import java.util.List;

/**
 * JQL 预览结果：命中总数 + 前几条样例 issue（只读，不落库）。
 */
public record JiraSyncPreviewView(int total, List<IssueBrief> issues) {

    /** 样例 issue 摘要 */
    public record IssueBrief(String key, String summary, String issueType, String status,
                             Instant created, Instant updated) {}
}
