package com.devmind.decisionlab.checkpoint.dto;

import java.time.Instant;
import java.util.List;

/**
 * CAP-56 FR-07 闸门的当前状态（前端顶部那条横幅）。
 *
 * <p>{@code reason} 直接取自 {@code DecisionGate.unavailableReason()}——与知识库分诊按钮
 * 置灰用的是<b>同一个上游</b>。页面自己拼一套判断的话，迟早出现"页面说可用、按钮是灰的"
 * 这种最让人没头绪的状态。（{@code open=false} 时 {@code reason} 必有值。）</p>
 *
 * @param open      true = 放行中（分诊可用）
 * @param reason    未放行的原因（open=true 时为空）
 * @param serving   正在放行的产物（通常每槽位一份；闸门未通过时为空）
 * @param checkedAt 这次询问的时间（页面显示"刚刚查过"，避免人以为横幅是缓存的）
 */
public record CheckpointGateView(boolean open, String reason, List<CheckpointView> serving,
                                 Instant checkedAt) {
}
