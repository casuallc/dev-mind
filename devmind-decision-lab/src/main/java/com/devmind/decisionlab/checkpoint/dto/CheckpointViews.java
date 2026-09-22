package com.devmind.decisionlab.checkpoint.dto;

import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;

/**
 * CAP-56 产物实体 → 视图。
 *
 * <p>列表行不带三坨大 JSON 的正文（理由见 {@link CheckpointView}），只解出"有没有"；
 * 详情页才把正文解析出来（{@code CheckpointService.detail}）。</p>
 */
public final class CheckpointViews {

    private CheckpointViews() {
    }

    public static CheckpointView of(DecisionCheckpointEntity e) {
        return new CheckpointView(e.getId(), e.getName(), e.getServeSlot(), e.getKind(),
                kindLabel(e.getKind()), e.getSourcePath(), e.getNodeId(), e.getFingerprintPath(),
                e.getFingerprintBytes(), e.getFingerprintSha256(), e.isVerified(), e.getVerifiedBy(),
                e.getVerifiedAt(), e.getVerifiedNote(), hasText(e.getMetricsJson()),
                hasText(e.getCalibrationJson()), e.getServeCheckedAt(), e.getServeCheckStatus(),
                e.getCreatedBy(), e.getCreatedAt(), e.getNote());
    }

    public static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case DecisionCheckpointEntity.KIND_BASE -> "官方基础";
            case DecisionCheckpointEntity.KIND_FINETUNED -> "微调产物";
            default -> kind == null ? "" : kind;
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
