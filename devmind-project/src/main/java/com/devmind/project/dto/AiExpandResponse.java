package com.devmind.project.dto;

/**
 * CAP-105 需求 AI 扩展结果：title/description 必填；type 不在词表（FEATURE/BUG/IMPROVEMENT/TASK）
 * 时为 null（前端保留表单原值）；sessionId 为 one-shot 执行痕迹（会话列表可回溯）。
 */
public record AiExpandResponse(String title, String type, String description, String sessionId) {
}
