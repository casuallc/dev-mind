package com.devmind.task.engine;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.task.api.TaskContext;
import com.devmind.task.model.AsyncTaskRunEntity;

/**
 * TaskContext 框架实现：log 实时广播（topic=runId）+ StringBuilder 攒底；
 * 取消标记由超时/取消路径置位，handler 长循环周期检查。
 */
public class TaskContextImpl implements TaskContext {

    private final AsyncTaskRunEntity run;
    private final ExecutionLogHub hub;
    private final StringBuilder logs = new StringBuilder();
    private volatile boolean cancelled;

    public TaskContextImpl(AsyncTaskRunEntity run, ExecutionLogHub hub) {
        this.run = run;
        this.hub = hub;
    }

    @Override
    public String taskId() {
        return run.getTaskId();
    }

    @Override
    public String runId() {
        return run.getId();
    }

    @Override
    public int runSeq() {
        return run.getRunSeq();
    }

    @Override
    public int attempt() {
        return run.getAttempt();
    }

    @Override
    public synchronized void log(String line) {
        String text = line == null ? "" : line;
        hub.publishLog(run.getId(), text);
        logs.append(text).append('\n');
    }

    @Override
    public void progress(String message) {
        hub.publishEvent(run.getId(), "progress", message == null ? "" : message);
    }

    @Override
    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    public void markCancelled() {
        this.cancelled = true;
    }

    /** 攒底日志（终态落库用） */
    public synchronized String logs() {
        return logs.toString();
    }
}
