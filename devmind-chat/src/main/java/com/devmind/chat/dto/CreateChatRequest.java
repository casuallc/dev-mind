package com.devmind.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 起问答请求（CAP-30）：首条消息即初始 prompt。
 *
 * @param message        首条消息（必填）
 * @param model          模型（可空 = 场景预设 / 配置默认）
 * @param permissionMode 权限模式（可空 = 场景预设 / 配置默认）
 * @param agentNodeId    执行节点（可空 = 场景预设 / 平台默认节点，皆无命中创建失败 409；
 *                       CAP-34 起保留值 "local" 已废除，传入报 400）
 * @param scenarioCode   CAP-33 FR-05：场景 code（可空）；场景骨架渲染后作初始 prompt，
 *                       绑定资产经装配管线注入沙箱
 */
public record CreateChatRequest(@NotBlank(message = "首条消息不能为空") String message,
                                String model, String permissionMode, String agentNodeId,
                                String scenarioCode) {

    /** 兼容构造器：CAP-30~34 调用点（无场景）。 */
    public CreateChatRequest(String message, String model, String permissionMode, String agentNodeId) {
        this(message, model, permissionMode, agentNodeId, null);
    }
}
