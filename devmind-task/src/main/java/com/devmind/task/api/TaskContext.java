package com.devmind.task.api;

/**
 * CAP-80 FR-01 任务执行上下文：日志/进度回写与取消感知。
 * 实现由框架提供，线程安全（执行线程写日志，管理线程可能置取消标记）。
 */
public interface TaskContext {

    String taskId();

    String runId();

    /** 第几轮执行（1 起，max_runs 口径） */
    int runSeq();

    /** 该轮第几次尝试（1 起，重试递增） */
    int attempt();

    /** 实时日志行：WS 广播 + 攒底，终态落 async_task_runs.logs */
    void log(String line);

    /** 进度回写：WS 业务事件帧（{"type":"progress"}），不落库 */
    void progress(String message);

    /**
     * 取消感知：超时中断/任务取消后置 true。
     * 长循环 handler 应周期检查并尽快返回（对齐 {@link AsyncTaskHandler} 中断契约）。
     */
    boolean isCancelled();
}
