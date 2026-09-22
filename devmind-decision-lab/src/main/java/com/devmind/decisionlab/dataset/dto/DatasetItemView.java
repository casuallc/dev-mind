package com.devmind.decisionlab.dataset.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-56 评测样本列表行：只带"扫一眼"用得上的信息（标题、组别、标注情况），
 * 三份 JSON 全文走 {@link DatasetItemDetail}——列表页一页几十条，
 * 每条都拖 state/questions/gold 全文的话，首屏全是在传没人看的字。
 */
public record DatasetItemView(
        Long id,
        String caseGroup,
        String caseGroupLabel,
        String source,
        Long originRecordId,
        String note,
        /** 从 state 里挑出来的标题（拿不到时给占位，界面不至于空一格） */
        String title,
        /** 已标注且能落上题面的题 id（gold 落不上的题不算，否则覆盖数会虚高） */
        List<String> annotatedQuestions,
        /** 对照组标签与 state 内容是否相符；不符时这里是原因（冻结会被拒） */
        String caseGroupIssue,
        Instant createdAt) {
}
