package com.devmind.session.dto;

/**
 * CAP-115 M2 终端工作区 Tab 补全请求体。
 *
 * @param input 当前输入行
 * @param cwd   相对工作区聚合根的 POSIX 路径（空 = 根）
 */
public record TerminalWsCompleteRequest(String input, String cwd) {
}
