package com.devmind.task.api;

import java.time.Instant;

/**
 * CAP-80 M4 REST 创建/编辑任务请求（POST/PUT /api/async-tasks，仅 ADMIN）。
 * 一次性形态（IMMEDIATE/DELAY）固定 max_runs=1；周期形态（FIXED_DELAY/CRON）maxRuns 可空=不限。
 * 布尔字段不存在（Jackson 3 红线），全部可空——按形态校验必填项（DELAY 要 runAt 等）。
 */
public record TaskUpsertRequest(
        String name,
        /** handler type（分发键，须已注册） */
        String type,
        /** JSON 快照（可空=handler 用默认值；非空必须是合法 JSON） */
        String payload,
        /** IMMEDIATE（默认）/DELAY/FIXED_DELAY/CRON */
        String scheduleKind,
        /** DELAY 指定时刻（统一格式或 ISO-8601，走全局 Instant 反序列化） */
        Instant runAt,
        /** FIXED_DELAY 间隔毫秒 */
        Long intervalMs,
        /** CRON 六段式 */
        String cronExpr,
        /** 周期形态执行次数上限（null=不限） */
        Integer maxRuns,
        /** RUNNER 类任务显式执行节点（须已登记；LOCAL 类忽略） */
        String agentNodeId,
        String projectId,
        Integer retryMaxAttempts,
        /** FIXED/EXPONENTIAL */
        String retryBackoff,
        Long retryIntervalMs,
        Long timeoutMs,
        /** SKIP/QUEUE */
        String overlapPolicy) {
}
