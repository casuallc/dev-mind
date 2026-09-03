package com.devmind.agent.dto;

/**
 * 手动升级结果（恒 200 返回，业务结果走 status 字段，前端按值分支提示）。
 * status: ACCEPTED（已受理，节点将重启）/ BUSY（有活跃会话已推迟）/
 * ALREADY_LATEST（已是当前版本）/ REJECTED（runner 拒绝，reason 进 message）。
 */
public record UpgradeResultView(String status, String message, Integer activeSessions) {
}
