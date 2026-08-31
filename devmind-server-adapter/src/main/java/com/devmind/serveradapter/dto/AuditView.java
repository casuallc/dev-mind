package com.devmind.serveradapter.dto;

import java.time.Instant;

/**
 * 执行审计记录视图（CAP-07 FR-06）。command 为模板渲染结果，不含任何凭证。
 */
public record AuditView(
        Long id,
        String projectId,
        Long serverId,
        String serverName,
        String accessType,
        String action,
        String templateCode,
        String capability,
        String command,
        Integer exitCode,
        boolean success,
        String detail,
        Long durationMs,
        Instant createdAt) {
}
