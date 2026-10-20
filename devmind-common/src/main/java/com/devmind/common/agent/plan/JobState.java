package com.devmind.common.agent.plan;

/**
 * 异步作业终态（CAP-76 FR-35）：{@link JobTerminalProbe} 的判定结果。
 * UNKNOWN = 记录不存在或无法判定（调用方按 RUNNING 口径继续等待）。
 */
public enum JobState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
