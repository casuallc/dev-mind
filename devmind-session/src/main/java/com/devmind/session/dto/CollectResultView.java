package com.devmind.session.dto;

import java.util.List;

/**
 * CAP-39：按需回传结果（POST /sessions/{id}/outputs/collect）。无论收集成败都带当前已存列表——
 * 前端一次调用拿到最新文件与提示。
 *
 * @param collected runner 是否回传了新产出
 * @param message   降级/失败提示（历史本机会话、节点离线、老 runner、runner 侧失败）；成功为 null
 */
public record CollectResultView(boolean collected, String message, List<OutputFileView> files) {
}
