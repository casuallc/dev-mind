package com.devmind.project.dto;

/**
 * CAP-105 AI 扩充响应：description 为生成的结构化 Markdown 正文（已按上限截断）；
 * truncated=true 表示模型输出超出 {@code MAX_DESCRIPTION_LENGTH} 被截断，前端提示人工补全。
 * （Jackson 3 红线：布尔必用包装类型。）
 */
public record AiExpandResponse(String description, Boolean truncated) {
}
