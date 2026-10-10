package com.devmind.flow.verify.dto;

/**
 * CAP-73 验证触发请求（人工与 agent 通道共用 body）。
 *
 * @param note    验证备注（落 verify_runs.note）
 * @param modules 编译范围 hint（AI 判断；原样经 VERIFY_MODULES 透传进 build_steps，空 = 全量）
 */
public record VerifyTriggerRequest(String note, String modules) {
}
