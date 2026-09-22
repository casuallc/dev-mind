package com.devmind.decisionlab.dataset.dto;

import java.time.Instant;

/**
 * CAP-56 评测集列表行。{@code questionSetVersion} 冻结前为空——草稿期题面版本还没定下来，
 * 界面显示"未定"比显示一个待变的值诚实。
 */
public record DatasetView(
        Long id,
        String name,
        String kind,
        String kindLabel,
        int version,
        boolean frozen,
        String questionSetVersion,
        int itemCount,
        String note,
        String createdBy,
        Instant createdAt,
        String frozenBy,
        Instant frozenAt) {
}
