package com.devmind.task.engine;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.TaskContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-80 FR-01 分发器：同 type 多注册启动期 fail-fast（静默覆盖会把任务派给错误执行体）；
 * 未知 type 提交时 400。
 */
class TaskDispatcherTest {

    private static AsyncTaskHandler stub(String type) {
        return new AsyncTaskHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void execute(String payload, TaskContext ctx) {
            }
        };
    }

    @Test
    void dispatchesByType() {
        AsyncTaskHandler a = stub("a");
        TaskDispatcher d = new TaskDispatcher(List.of(a, stub("b")));
        assertTrue(d.exists("a"));
        assertSame(a, d.handlerOrNull("a"));
    }

    @Test
    void duplicateTypeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TaskDispatcher(List.of(stub("dup"), stub("dup"))));
        assertTrue(e.getMessage().contains("dup"));
    }

    @Test
    void requireRejectsUnknownTypeWith400() {
        TaskDispatcher d = new TaskDispatcher(List.of(stub("a")));
        DevMindException e = assertThrows(DevMindException.class, () -> d.require("nope"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }
}
