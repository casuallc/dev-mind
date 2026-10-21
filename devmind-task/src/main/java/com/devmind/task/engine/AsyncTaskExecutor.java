package com.devmind.task.engine;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.model.AsyncTaskRunEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import com.devmind.task.repo.AsyncTaskRunRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CAP-80 FR-03 执行内核：虚拟线程池（每 run 两条 VT——监督线程认领/等超时，
 * 工作线程跑 handler，超时 cancel(true) 协作式中断）。
 *
 * <p>中断是协作式的：handler 不理会中断时线程继续存活，终态流转的乐观条件
 * （QUEUED/RUNNING 内才可翻转）保证收割器判 FAILED 后迟到的 handler 结果被丢弃，
 * 不翻转终态。</p>
 */
@Component
public class AsyncTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskExecutor.class);

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    /** 在跑工作线程句柄（runId → future），任务 cancel 级联中断用 */
    private final Map<String, Future<?>> workByRun = new ConcurrentHashMap<>();

    private final TaskDispatcher dispatcher;
    private final RunLifecycleService lifecycle;
    private final AsyncTaskRepository taskRepo;
    private final AsyncTaskRunRepository runRepo;
    private final ExecutionLogHub hub;
    private final UserRepository userRepo;

    public AsyncTaskExecutor(TaskDispatcher dispatcher, RunLifecycleService lifecycle,
                             AsyncTaskRepository taskRepo, AsyncTaskRunRepository runRepo,
                             ExecutionLogHub hub, UserRepository userRepo) {
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.hub = hub;
        this.userRepo = userRepo;
    }

    /** 派发 run：监督线程立即返回式提交（tick 线程不阻塞）。 */
    public void launch(String runId) {
        pool.submit(() -> supervise(runId));
    }

    /** 任务 cancel 级联：中断在跑工作线程（协作式），终态由 cancelRun 落 */
    public void interrupt(String runId) {
        Future<?> work = workByRun.get(runId);
        if (work != null) {
            work.cancel(true);
        }
    }

    private void supervise(String runId) {
        if (!lifecycle.startRun(runId)) {
            return; // 已被并发认领或已终态
        }
        AsyncTaskRunEntity run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        AsyncTaskEntity task = taskRepo.findById(run.getTaskId()).orElse(null);
        if (task == null) {
            lifecycle.failRun(runId, "任务记录不存在: " + run.getTaskId(), false, null);
            return;
        }
        AsyncTaskHandler handler = dispatcher.handlerOrNull(task.getTaskType());
        if (handler == null) {
            lifecycle.failRun(runId, "handler 未注册: " + task.getTaskType(), false, null);
            return;
        }
        TaskContextImpl ctx = new TaskContextImpl(run, hub);
        long timeoutMs = task.getTimeoutMsOrDefault();
        Future<?> work = pool.submit(() -> {
            withActorContext(task.getActor(), () -> {
                handler.execute(task.getPayload(), ctx);
                return null;
            });
            return null;
        });
        workByRun.put(runId, work);
        try {
            work.get(timeoutMs, TimeUnit.MILLISECONDS);
            lifecycle.succeedRun(runId, ctx.logs());
        } catch (TimeoutException e) {
            ctx.markCancelled();
            work.cancel(true);
            lifecycle.failRun(runId, "执行超时（" + timeoutMs + "ms），已中断", true, ctx.logs());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            boolean retryable = !(cause instanceof NonRetryableTaskException);
            lifecycle.failRun(runId, summarize(cause), retryable, ctx.logs());
        } catch (CancellationException e) {
            // 任务 cancel 级联已置 CANCELLED，无需再流转
            log.debug("run {} 工作线程被取消", runId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 关闭中：不翻转状态，留给僵尸收割/重启扫描
        } finally {
            workByRun.remove(runId);
        }
    }

    /**
     * §7：框架线程内合成 SecurityContext（actor=任务登记人，角色取 users 表当前值），
     * handler 内调业务 Service 的权限边界 = 登记人既有权限。
     * 受检异常原样上抛（Callable 允许），ExecutionException.getCause() 即 handler 原异常，
     * NonRetryableTaskException 身份不被包装吃掉。
     */
    private void withActorContext(String actor, java.util.concurrent.Callable<Void> body) throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        UserEntity user = actor == null ? null : userRepo.findByUsername(actor).orElse(null);
        if (user != null) {
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    new DevMindPrincipal(user.getUsername(), user.getRole()), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))));
            SecurityContextHolder.setContext(context);
        }
        try {
            body.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 失败摘要：异常首行 + 前若干关键帧（入 error 列，截断在 finish 前再做一次） */
    private String summarize(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(lines.length, 8);
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i].strip()).append('\n');
        }
        return sb.toString().strip();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
