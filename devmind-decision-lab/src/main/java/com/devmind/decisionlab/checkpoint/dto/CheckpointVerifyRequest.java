package com.devmind.decisionlab.checkpoint.dto;

/**
 * CAP-56 FR-07 人工确认放行 / 撤销放行。
 *
 * <p>两个动作都<b>必须带一句话</b>：放行要写"凭什么"（跑了哪个集、哪个指标、与基线比如何），
 * 撤销要写"为什么"。这不是形式主义——闸门是这套链路里唯一的机械保证，而它靠人来按；
 * 一个不需要理由就能按下、也不需要理由就能撤回的开关，出问题时复盘只剩"某天某个人点了一下"。</p>
 *
 * @param note   验证通过时的判断依据（必填）
 * @param reason 撤销放行的原因（必填；只在撤销时用）
 */
public record CheckpointVerifyRequest(String note, String reason) {
}
