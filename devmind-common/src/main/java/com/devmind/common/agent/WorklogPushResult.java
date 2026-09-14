package com.devmind.common.agent;

/**
 * CAP-41 M3 工作日志空间远端备份 ack（worklog_push 帧的应答）：runner 对持久工作区
 * `{worklogRoot}/<owner>/` 执行 git push 后回此结果。
 *
 * @param ok     推送成功（远端已是最新 "up-to-date" 也算成功）
 * @param detail 成功摘要（如推送的分支与输出尾部，已经 runner 侧 sanitize 脱敏；ok=false 时为 null）
 * @param error  失败原因（已经 sanitize 脱敏；ok=true 时为 null）
 */
public record WorklogPushResult(boolean ok, String detail, String error) {

    public static WorklogPushResult ok(String detail) {
        return new WorklogPushResult(true, detail, null);
    }

    public static WorklogPushResult failed(String error) {
        return new WorklogPushResult(false, null, error);
    }
}
