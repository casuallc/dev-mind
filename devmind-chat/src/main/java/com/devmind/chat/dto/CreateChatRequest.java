package com.devmind.chat.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
 * @param knowledgeBaseId CAP-46 FR-01 兼容字段：单库 id（可空）；CAP-84 起等价于
 *                       knowledgeBaseIds 只含一个库，落库仍取多库列表第一个
 * @param executor        CAP-49：执行体 AGENT（默认、可空）| MODEL（服务端直连已接入的 CHAT 端点，
 *                        不需要任何节点在线）；其它值 400
 * @param modelEndpointId CAP-49：模型执行体的对话端点 id（可空 = 平台默认 CHAT 端点，无默认 409）；
 *                        显式传 MODEL 下的非 CHAT 端点 / 已停用端点一律 400
 * @param knowledgeBaseIds CAP-84 FR-16：多库绑定（最多 5 个）；非空即知识会话，仅支持 MODEL 执行体
 * @param knowledgeMode   CAP-84 FR-17：ANSWER（默认）| SOLUTION；仅知识会话有效
 * @param knowledgeOptions CAP-84 FR-16：检索过滤与引用要求（可空）
 */
public record CreateChatRequest(@NotBlank(message = "首条消息不能为空") String message,
                                String model, String permissionMode, String agentNodeId,
                                String scenarioCode, Long knowledgeBaseId,
                                String executor, Long modelEndpointId,
                                List<Long> knowledgeBaseIds, String knowledgeMode,
                                KnowledgeOptions knowledgeOptions) {

    /** 兼容构造器：CAP-49 调用点（单库兼容字段，无执行体维度 = AGENT）。 */
    public CreateChatRequest(String message, String model, String permissionMode, String agentNodeId,
                             String scenarioCode, Long knowledgeBaseId, String executor, Long modelEndpointId) {
        this(message, model, permissionMode, agentNodeId, scenarioCode, knowledgeBaseId,
                executor, modelEndpointId, null, null, null);
    }

    /** 兼容构造器：CAP-33/46 调用点（无执行体维度 = AGENT）。 */
    public CreateChatRequest(String message, String model, String permissionMode, String agentNodeId,
                             String scenarioCode, Long knowledgeBaseId) {
        this(message, model, permissionMode, agentNodeId, scenarioCode, knowledgeBaseId, null, null);
    }

    /** 兼容构造器：CAP-33 调用点（未绑库）。 */
    public CreateChatRequest(String message, String model, String permissionMode, String agentNodeId,
                             String scenarioCode) {
        this(message, model, permissionMode, agentNodeId, scenarioCode, null, null, null);
    }

    /** 兼容构造器：CAP-30~34 调用点（无场景）。 */
    public CreateChatRequest(String message, String model, String permissionMode, String agentNodeId) {
        this(message, model, permissionMode, agentNodeId, null, null, null, null);
    }
}
