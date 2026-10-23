package com.devmind.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-84 FR-25 负面反馈按文档聚合（管理页「反馈治理」）。
 *
 * @param since         统计起点
 * @param feedbackTotal 区间内全部反馈数
 * @param negativeTotal 区间内无用反馈数
 * @param docs          按无用数降序的文档列表
 */
public record NegativeFeedbackReportView(
        Instant since,
        long feedbackTotal,
        long negativeTotal,
        List<Doc> docs) {

    public record Doc(
            Long entryId,
            String entryName,
            Long kbId,
            long negativeCount,
            long totalCount,
            double negativeRate,
            List<ReasonCount> reasons,
            Instant lastNegativeAt) {
    }

    public record ReasonCount(String reason, long count) {
    }
}
