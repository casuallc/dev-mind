package com.devmind.task.builtin;

import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.api.TaskContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 内置回显任务 {@code builtin.echo}：打日志/按毫秒数睡眠/按需失败——框架自测与 E2E 用，
 * 也作为最小 handler 范例。payload JSON：
 * {@code {"message": "...", "lines": 3, "sleepMs": 4000, "fail": false, "nonRetryable": false}}
 */
@Component
public class EchoTaskHandler implements AsyncTaskHandler {

    public static final String TYPE = "builtin.echo";

    private final ObjectMapper mapper;

    public EchoTaskHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(String payload, TaskContext ctx) throws Exception {
        JsonNode p = payload == null || payload.isBlank() ? mapper.createObjectNode() : mapper.readTree(payload);
        String message = p.path("message").asText("echo");
        int lines = Math.max(1, p.path("lines").asInt(3));
        long sleepMs = Math.max(0, p.path("sleepMs").asLong(0));
        boolean fail = p.path("fail").asBoolean(false);
        boolean nonRetryable = p.path("nonRetryable").asBoolean(false);

        ctx.log("echo 开始: message=" + message);
        for (int i = 1; i <= lines; i++) {
            if (ctx.isCancelled()) {
                ctx.log("检测到取消标记，协作式退出");
                return;
            }
            ctx.log("line " + i + "/" + lines);
            ctx.progress(i + "/" + lines);
            if (sleepMs > 0) {
                Thread.sleep(sleepMs / lines);
            }
        }
        if (fail) {
            if (nonRetryable) {
                throw new NonRetryableTaskException("echo 不可重试失败: " + message);
            }
            throw new IllegalStateException("echo 故意失败: " + message);
        }
        ctx.log("echo 完成: " + message);
    }
}
