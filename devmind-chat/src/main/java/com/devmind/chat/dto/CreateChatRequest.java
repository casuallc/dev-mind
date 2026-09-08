package com.devmind.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 起问答请求（CAP-30）：首条消息即初始 prompt。
 *
 * @param message        首条消息（必填）
 * @param model          模型（可空 = 配置/CLI 默认）
 * @param permissionMode 权限模式（可空 = 配置默认）
 * @param agentNodeId    执行节点（可空 = 平台默认节点，无默认则创建失败 409；
 *                       CAP-34 起保留值 "local" 已废除，传入报 400）
 */
public record CreateChatRequest(@NotBlank(message = "首条消息不能为空") String message,
                                String model, String permissionMode, String agentNodeId) {
}
