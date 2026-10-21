package com.devmind.task.api;

/**
 * CAP-80 FR-10 周期任务（FIXED_DELAY/CRON）调度规格。
 * FIXED_DELAY 为 fixed-delay 语义：下次执行 = 本轮终态时刻 + intervalMs（随耗时漂移，非固定速率）。
 */
public final class ScheduleSpec {

    /** FIXED_DELAY / CRON */
    private final String kind;
    private final String name;
    private final Long intervalMs;
    private final String cronExpr;
    private final Integer maxRuns;
    private final String projectId;
    private final Integer retryMaxAttempts;
    private final String retryBackoff;
    private final Long retryIntervalMs;
    private final Long timeoutMs;
    private final String overlapPolicy;

    private ScheduleSpec(Builder b) {
        this.kind = b.kind;
        this.name = b.name;
        this.intervalMs = b.intervalMs;
        this.cronExpr = b.cronExpr;
        this.maxRuns = b.maxRuns;
        this.projectId = b.projectId;
        this.retryMaxAttempts = b.retryMaxAttempts;
        this.retryBackoff = b.retryBackoff;
        this.retryIntervalMs = b.retryIntervalMs;
        this.timeoutMs = b.timeoutMs;
        this.overlapPolicy = b.overlapPolicy;
    }

    public static Builder builder(String kind) {
        return new Builder(kind);
    }

    public static Builder fixedDelay(long intervalMs) {
        return new Builder("FIXED_DELAY").intervalMs(intervalMs);
    }

    public static Builder cron(String cronExpr) {
        return new Builder("CRON").cronExpr(cronExpr);
    }

    public String getKind() { return kind; }
    public String getName() { return name; }
    public Long getIntervalMs() { return intervalMs; }
    public String getCronExpr() { return cronExpr; }
    /** 执行次数上限（null=不限） */
    public Integer getMaxRuns() { return maxRuns; }
    public String getProjectId() { return projectId; }
    public Integer getRetryMaxAttempts() { return retryMaxAttempts; }
    public String getRetryBackoff() { return retryBackoff; }
    public Long getRetryIntervalMs() { return retryIntervalMs; }
    public Long getTimeoutMs() { return timeoutMs; }
    public String getOverlapPolicy() { return overlapPolicy; }

    public static final class Builder {
        private final String kind;
        private String name;
        private Long intervalMs;
        private String cronExpr;
        private Integer maxRuns;
        private String projectId;
        private Integer retryMaxAttempts;
        private String retryBackoff;
        private Long retryIntervalMs;
        private Long timeoutMs;
        private String overlapPolicy;

        private Builder(String kind) {
            this.kind = kind;
        }

        public Builder name(String v) { name = v; return this; }
        public Builder intervalMs(long v) { intervalMs = v; return this; }
        public Builder cronExpr(String v) { cronExpr = v; return this; }
        public Builder maxRuns(int v) { maxRuns = v; return this; }
        public Builder projectId(String v) { projectId = v; return this; }
        public Builder retryMaxAttempts(int v) { retryMaxAttempts = v; return this; }
        public Builder retryBackoff(String v) { retryBackoff = v; return this; }
        public Builder retryIntervalMs(long v) { retryIntervalMs = v; return this; }
        public Builder timeoutMs(long v) { timeoutMs = v; return this; }
        public Builder overlapPolicy(String v) { overlapPolicy = v; return this; }

        public ScheduleSpec build() {
            return new ScheduleSpec(this);
        }
    }
}
