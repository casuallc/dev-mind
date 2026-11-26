package com.devmind.agent.dto;

/**
 * 隧道人工重建结果（POST /api/agent-nodes/{id}/tunnel-reset）。
 * status=ACCEPTED 表示指令已下发（runner 重连结果看隧道在线状态，数秒内刷新）。
 */
public record TunnelResetView(String status, String message) {
}
