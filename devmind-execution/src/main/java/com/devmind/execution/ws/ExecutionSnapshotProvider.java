package com.devmind.execution.ws;

import java.util.Map;

/**
 * 执行日志快照提供者（P0-1）：WS 连接建立时按 topic 查历史日志与终态。
 * 各业务模块实现（如 build 从 BuildRepository 查 logsText/status；
 * deploy/test 用 {@link ExecutionSnapshot#extra} 捎带业务快照字段——步骤列表/用例结果等）。
 */
@FunctionalInterface
public interface ExecutionSnapshotProvider {

    /** @return null 表示 topic 不存在（连接将被关闭） */
    ExecutionSnapshot lookup(String topic);

    /**
     * @param logsText 已持久化的日志快照（可为空）
     * @param status   当前状态（QUEUED/RUNNING/SUCCESS/FAILED/…）
     * @param terminal 是否终态（true 时快照后立即补发 done 帧）
     * @param extra    合并进 snapshot 帧的业务字段（如 deploy 的 steps/currentStep，test 的 results/baseUrl；可为空）
     */
    record ExecutionSnapshot(String logsText, String status, boolean terminal, Map<String, Object> extra) {

        public ExecutionSnapshot(String logsText, String status, boolean terminal) {
            this(logsText, status, terminal, Map.of());
        }
    }
}
