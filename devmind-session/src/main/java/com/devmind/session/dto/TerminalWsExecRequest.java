package com.devmind.session.dto;

/**
 * CAP-115 M2 终端工作区命令执行请求体。
 *
 * @param command   命令全文（必填）
 * @param cwd       相对工作区聚合根的 POSIX 路径（空 = 根）
 * @param confirmed 危险命令二次确认标记：护栏命中（428）后用户确认 → true 重发放行
 */
public record TerminalWsExecRequest(String command, String cwd, Boolean confirmed) {
}
