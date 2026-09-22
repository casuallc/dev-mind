package com.devmind.decisionlab.checkpoint.dto;

import java.time.Instant;

/**
 * CAP-56 FR-06 产物列表行。
 *
 * <p><b>不带三坨大 JSON</b>（指标 / 校准 / 自检报告）：列表一页二十行，指标报告正文里还有逐题分解，
 * 全解析一遍纯属浪费；需要正文的详情页单独取（{@link CheckpointDetail}）。这里只给
 * {@code hasMetrics} / {@code hasCalibration} 两个"有没有"的信号，列表上够判断"这份产物评过没有"
 * ——而"评过没有"正是准入时第一个要看的。</p>
 */
public record CheckpointView(
        Long id,
        String name,
        String serveSlot,
        String kind,
        String kindLabel,
        String sourcePath,
        String nodeId,
        String fingerprintPath,
        Long fingerprintBytes,
        String fingerprintSha256,
        boolean verified,
        String verifiedBy,
        Instant verifiedAt,
        String verifiedNote,
        boolean hasMetrics,
        boolean hasCalibration,
        Instant serveCheckedAt,
        /** 上次自检的结论状态（OK/WARN/FAIL，没跑过为空） */
        String serveCheckStatus,
        String createdBy,
        Instant createdAt,
        String note) {
}
