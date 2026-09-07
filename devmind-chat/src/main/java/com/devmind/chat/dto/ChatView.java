package com.devmind.chat.dto;

import com.devmind.common.agent.runtime.SessionState;

import java.time.Instant;

/**
 * 问答视图（CAP-30）。status 为持久化状态字符串，state 为实时状态枚举（运行中取内核状态）。
 */
public record ChatView(String id, String title, String status, SessionState state,
                       Long pid, String model, String permissionMode, String summary,
                       String agentNodeId, String createdBy,
                       Instant createdAt, Instant updatedAt, Instant finishedAt) {
}
