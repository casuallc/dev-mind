package com.devmind.execution.ws;

/**
 * 执行日志快照提供者（P0-1）：WS 连接建立时按 topic 查历史日志与终态。
 * 各业务模块实现（如 build 从 BuildRepository 查 logsText/status）。
 */
@FunctionalInterface
public interface ExecutionSnapshotProvider {

    /** @return null 表示 topic 不存在（连接将被关闭） */
    ExecutionSnapshot lookup(String topic);

    /**
     * @param logsText 已持久化的日志快照（可为空）
     * @param status   当前状态（QUEUED/RUNNING/SUCCESS/FAILED/…）
     * @param terminal 是否终态（true 时快照后立即补发 done 帧）
     */
    record ExecutionSnapshot(String logsText, String status, boolean terminal) {
    }
}
