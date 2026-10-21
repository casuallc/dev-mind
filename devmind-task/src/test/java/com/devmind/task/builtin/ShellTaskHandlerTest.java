package com.devmind.task.builtin;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.api.TaskContext;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-80 M4 builtin.shell：payload 解析、路由链与 exec 帧组装、退出码/超时/拉起失败映射。
 * 全部内存打桩（runner 链路正确性由 E2E 兜住——真 runner 过 execAllowlist）。
 */
class ShellTaskHandlerTest {

    private final AgentNodeRouter router = mock(AgentNodeRouter.class);
    private final AgentNodeConnector connector = mock(AgentNodeConnector.class);
    private final AsyncTaskRepository taskRepo = mock(AsyncTaskRepository.class);
    private final List<String> logs = new ArrayList<>();
    private final AtomicReference<AgentExecCommand> sent = new AtomicReference<>();

    private ShellTaskHandler handler;

    private final TaskContext ctx = new TaskContext() {
        @Override public String taskId() { return "t1"; }
        @Override public String runId() { return "r1"; }
        @Override public int runSeq() { return 1; }
        @Override public int attempt() { return 1; }
        @Override public void log(String line) { logs.add(line); }
        @Override public void progress(String message) { }
        @Override public boolean isCancelled() { return false; }
    };

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentNodeConnector> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connector);
        handler = new ShellTaskHandler(new ObjectMapper(), router, provider, taskRepo);
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId("t1");
        task.setTaskType(ShellTaskHandler.TYPE);
        when(taskRepo.findById("t1")).thenReturn(Optional.of(task));
        when(router.route(isNull(), isNull(), eq(List.of()))).thenReturn("3");
        when(connector.exec(eq("3"), any(AgentExecCommand.class), any())).thenAnswer(inv -> {
            sent.set(inv.getArgument(1));
            return new AgentExecResult(0, false, null);
        });
    }

    @Test
    void metadataDeclaresRunnerExecutor() {
        assertEquals(ShellTaskHandler.TYPE, handler.type());
        assertEquals(ShellTaskHandler.EXECUTOR_RUNNER, handler.executor());
        assertTrue(handler.payloadTemplate().contains("command"));
    }

    @Test
    void blankCommandIsNonRetryable() {
        assertThrows(NonRetryableTaskException.class, () -> handler.execute("{}", ctx));
        assertThrows(NonRetryableTaskException.class, () -> handler.execute(null, ctx));
    }

    @Test
    void execFrameAssembledFromPayloadAndTask() throws Exception {
        handler.execute("{\"command\":\"echo hi\",\"workingDir\":\"sub\",\"env\":{\"A\":\"1\"}}", ctx);
        AgentExecCommand cmd = sent.get();
        assertEquals("r1", cmd.execId(), "execId = runId（日志帧路由键）");
        assertEquals("echo hi", cmd.command());
        assertEquals("sub", cmd.workingDir());
        assertEquals(Map.of("A", "1"), cmd.env());
        assertEquals(1800, cmd.timeoutSec(), "任务 timeoutMs 缺省 30min → cap 1800s");
        assertEquals(null, cmd.repo(), "repo=null 不准备代码，runner 本地目录执行");
        assertTrue(logs.stream().anyMatch(l -> l.contains("下发节点 3")));
    }

    @Test
    void payloadTimeoutSecWinsOverDefault() throws Exception {
        handler.execute("{\"command\":\"echo hi\",\"timeoutSec\":60}", ctx);
        assertEquals(60, sent.get().timeoutSec());
    }

    @Test
    void taskTimeoutBelowCapBecomesExecTimeout() throws Exception {
        AsyncTaskEntity task = taskRepo.findById("t1").orElseThrow();
        task.setTimeoutMs(120_000L);
        handler.execute("{\"command\":\"echo hi\"}", ctx);
        assertEquals(120, sent.get().timeoutSec());
    }

    @Test
    void nonZeroExitThrowsRetryable() {
        when(connector.exec(anyString(), any(AgentExecCommand.class), any()))
                .thenReturn(new AgentExecResult(2, false, null));
        Exception e = assertThrows(IllegalStateException.class,
                () -> handler.execute("{\"command\":\"exit 2\"}", ctx));
        assertTrue(e.getMessage().contains("exit=2"));
        assertTrue(!(e instanceof NonRetryableTaskException), "非零退出可重试（FR-04 策略）");
    }

    @Test
    void timedOutAndErrorSurfaceInMessage() {
        when(connector.exec(anyString(), any(AgentExecCommand.class), any()))
                .thenReturn(new AgentExecResult(-1, true, null));
        Exception e = assertThrows(IllegalStateException.class,
                () -> handler.execute("{\"command\":\"sleep 99\"}", ctx));
        assertTrue(e.getMessage().contains("超时"));

        when(connector.exec(anyString(), any(AgentExecCommand.class), any()))
                .thenReturn(AgentExecResult.failed("命令被 execAllowlist 拒绝"));
        e = assertThrows(IllegalStateException.class,
                () -> handler.execute("{\"command\":\"rm -rf /\"}", ctx));
        assertTrue(e.getMessage().contains("execAllowlist"), "白名单拒绝文本直达 error/日志");
    }

    @Test
    void noNodeAvailablePropagates409() {
        when(router.route(isNull(), isNull(), eq(List.of())))
                .thenThrow(new DevMindException(com.devmind.common.exception.ErrorCode.CONFLICT, "无可用执行节点"));
        DevMindException e = assertThrows(DevMindException.class,
                () -> handler.execute("{\"command\":\"echo hi\"}", ctx));
        assertEquals(com.devmind.common.exception.ErrorCode.CONFLICT, e.getErrorCode());
    }

    @Test
    void explicitNodeGoesToRouter() throws Exception {
        AsyncTaskEntity task = taskRepo.findById("t1").orElseThrow();
        task.setAgentNodeId("7");
        when(router.route(eq("7"), isNull(), eq(List.of()))).thenReturn("7");
        when(connector.exec(eq("7"), any(AgentExecCommand.class), any()))
                .thenReturn(new AgentExecResult(0, false, null));
        handler.execute("{\"command\":\"echo hi\"}", ctx);
        assertTrue(logs.stream().anyMatch(l -> l.contains("下发节点 7")));
    }
}
