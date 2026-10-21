package com.devmind.task.api;

import java.time.Instant;

/**
 * CAP-80 FR-10 一次性任务（IMMEDIATE/DELAY）提交选项。一次性任务固定跑一次
 *（max_runs=1），重试/超时/重叠策略照常生效。
 */
public final class TaskOptions {

    private final String name;
    private final Instant runAt;
    private final String projectId;
    private final String agentNodeId;
    private final Integer retryMaxAttempts;
    private final String retryBackoff;
    private final Long retryIntervalMs;
    private final Long timeoutMs;
    private final String overlapPolicy;

    private TaskOptions(Builder b) {
        this.name = b.name;
        this.runAt = b.runAt;
        this.projectId = b.projectId;
        this.agentNodeId = b.agentNodeId;
        this.retryMaxAttempts = b.retryMaxAttempts;
        this.retryBackoff = b.retryBackoff;
        this.retryIntervalMs = b.retryIntervalMs;
        this.timeoutMs = b.timeoutMs;
        this.overlapPolicy = b.overlapPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() { return name; }
    /** 非空 = DELAY 形态；空 = IMMEDIATE */
    public Instant getRunAt() { return runAt; }
    public String getProjectId() { return projectId; }
    /** CAP-80 M4：执行节点（RUNNER 类任务显式指定；空=路由链兜底；LOCAL 类忽略） */
    public String getAgentNodeId() { return agentNodeId; }
    public Integer getRetryMaxAttempts() { return retryMaxAttempts; }
    public String getRetryBackoff() { return retryBackoff; }
    public Long getRetryIntervalMs() { return retryIntervalMs; }
    public Long getTimeoutMs() { return timeoutMs; }
    public String getOverlapPolicy() { return overlapPolicy; }

    public static final class Builder {
        private String name;
        private Instant runAt;
        private String projectId;
        private String agentNodeId;
        private Integer retryMaxAttempts;
        private String retryBackoff;
        private Long retryIntervalMs;
        private Long timeoutMs;
        private String overlapPolicy;

        public Builder name(String v) { name = v; return this; }
        /** 指定执行时刻（DELAY）；不设置即 IMMEDIATE */
        public Builder runAt(Instant v) { runAt = v; return this; }
        /** 相对延迟毫秒（换算为 runAt，DELAY） */
        public Builder delayMs(long ms) { runAt = Instant.now().plusMillis(ms); return this; }
        public Builder projectId(String v) { projectId = v; return this; }
        /** CAP-80 M4：显式执行节点（RUNNER 类任务；LOCAL 类忽略） */
        public Builder agentNodeId(String v) { agentNodeId = v; return this; }
        /** 每轮最大尝试次数（含首次），默认 1=不重试 */
        public Builder retryMaxAttempts(int v) { retryMaxAttempts = v; return this; }
        /** FIXED / EXPONENTIAL（见 RetryPolicy） */
        public Builder retryBackoff(String v) { retryBackoff = v; return this; }
        public Builder retryIntervalMs(long v) { retryIntervalMs = v; return this; }
        public Builder timeoutMs(long v) { timeoutMs = v; return this; }
        /** SKIP / QUEUE */
        public Builder overlapPolicy(String v) { overlapPolicy = v; return this; }

        public TaskOptions build() {
            return new TaskOptions(this);
        }
    }
}
