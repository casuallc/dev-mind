package com.devmind.session.dto;

/**
 * CAP-115 M2 打开终端工作区应答：会话 + 工作区键 + runner 侧实际路径/分支
 * （重发 terminal_ws_prepare 的回执；runner 重启后 open 即重新绑定）。
 *
 * @param session      终端会话（新建或从 SUSPENDED 复活的）
 * @param workspaceKey 工作区键（term-<短id> / req-<需求id>；终端帧的伪会话 id）
 * @param path         runner 侧聚合根绝对路径（展示用）
 * @param branch       当前检出分支
 */
public record TerminalOpenView(TerminalSessionView session, String workspaceKey,
                               String path, String branch) {
}
