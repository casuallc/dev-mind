package com.devmind.session.dto;

import java.time.Instant;

/**
 * CAP-115 M2 终端会话视图。
 *
 * @param id              terminal_sessions.id
 * @param workspaceId     所属终端工作区 id
 * @param userId          操作人
 * @param state           RUNNING / SUSPENDED / CLOSED
 * @param startedAt       打开时刻
 * @param lastHeartbeatAt 最近心跳
 * @param endedAt         关闭/挂起时刻
 */
public record TerminalSessionView(String id, String workspaceId, String userId, String state,
                                  Instant startedAt, Instant lastHeartbeatAt, Instant endedAt) {
}
