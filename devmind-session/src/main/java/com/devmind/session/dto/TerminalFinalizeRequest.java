package com.devmind.session.dto;

/**
 * CAP-115 M3 终端工作区收口请求。
 *
 * <p>confirmCode 为服务端硬校验的防误触确认码——必须等于该工作区的 workspaceKey
 * （前端弹窗要求用户手输键名，避免一键误合并到共享基线）。</p>
 *
 * @param discardChanges     true = 丢弃未提交改动（runner 侧 reset --hard + clean -fd 后再合并）
 * @param deleteRemoteBranch true = 收口成功后删除远端终端分支（默认保留）
 */
public record TerminalFinalizeRequest(
        String confirmCode,
        Boolean discardChanges,
        Boolean deleteRemoteBranch) {
}
