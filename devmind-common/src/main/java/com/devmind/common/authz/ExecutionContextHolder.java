package com.devmind.common.authz;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * CAP-108 执行上下文持有器：请求/工具/异步线程级 ThreadLocal。
 * 异步线程必须由派发点显式传递上下文（{@link #set}），任务恢复只凭持久化主体引用重建，
 * 不能沿用过期授权对象；不存在缺省身份。
 */
public final class ExecutionContextHolder {

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    private ExecutionContextHolder() {
    }

    public static Optional<ExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 设置上下文并返回恢复句柄（try-with-resources 用）；嵌套时恢复前一个。 */
    public static Scope set(ExecutionContext ctx) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(ctx);
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 无上下文时以登记的系统任务身份执行（内部自动链路：流程落地/会话自动建 WI/机器人建需求）；
     * 已有上下文（HTTP/工具线程）保持用户身份不覆盖。
     */
    public static <T> T withSystemIfAbsent(String taskName, Supplier<T> body) {
        if (current().isPresent()) {
            return body.get();
        }
        try (Scope ignored = set(ExecutionContext.system(taskName))) {
            return body.get();
        }
    }

    public static void runWithSystemIfAbsent(String taskName, Runnable body) {
        withSystemIfAbsent(taskName, () -> {
            body.run();
            return null;
        });
    }

    public static final class Scope implements AutoCloseable {
        private final ExecutionContext previous;
        private boolean closed;

        private Scope(ExecutionContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
