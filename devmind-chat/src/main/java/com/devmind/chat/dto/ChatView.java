package com.devmind.chat.dto;

import com.devmind.common.agent.runtime.SessionState;

import java.time.Instant;

/**
 * 问答视图（CAP-30）。status 为持久化状态字符串，state 为实时状态枚举（运行中取内核状态）。
 *
 * <p>CAP-49：{@code executor} 恒有值（历史行回落 AGENT）；{@code modelEndpointId/Name/Model}
 * 仅模型执行体有值——端点被停用/删除后 Name 为 null（会话本身仍可查看与删除，恢复提问会 409）。</p>
 */
public record ChatView(String id, String title, String status, SessionState state,
                       Long pid, String model, String permissionMode, String summary,
                       String agentNodeId, String createdBy,
                       Instant createdAt, Instant updatedAt, Instant finishedAt,
                       Long knowledgeBaseId,
                       String executor, Long modelEndpointId, String modelEndpointName,
                       String modelEndpointModel) {
}
