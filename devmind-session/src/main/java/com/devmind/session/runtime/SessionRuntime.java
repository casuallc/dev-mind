package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionState;
import com.devmind.session.proc.ProcessHelper;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单个会话的内存态：持有子进程，3 路虚拟线程（stdout 读 / stderr 读 / 事件落库由全局 saver 负责），
 * 环形缓冲、订阅者集合、状态锁。
 *
 * <p>stdout 必须始终被消费（否则 pipe 写满导致子进程阻塞）。状态事件也走事件流，
 * 因此 WS 订阅者与迟到回放都能看到状态/权限请求。</p>
 */
public class SessionRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionRuntime.class);

    private final String id;
    private final Process process;
    private final BufferedWriter stdin;
    private final ObjectMapper mapper;
    private final CliEventParser parser;
    private final SessionEventSaver saver;
    private final RuntimeListener listener;
    private final SessionProperties props;

    private final Object stdinLock = new Object();
    private final Object stateLock = new Object();
    private final ArrayDeque<SessionEvent> ring = new ArrayDeque<>();
    private final Set<Consumer<SessionEvent>> subscribers = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean exitHandled = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.RUNNING;
    private volatile boolean lastResultSuccess = false;
    private volatile String summary = "";
    private volatile String pendingPermissionRequestId;
    private volatile long lastActivityAt = System.currentTimeMillis();

    public SessionRuntime(String id, Process process, ObjectMapper mapper, CliEventParser parser,
                          SessionEventSaver saver, RuntimeListener listener, SessionProperties props) {
        this.id = id;
        this.process = process;
        this.mapper = mapper;
        this.parser = parser;
        this.saver = saver;
        this.listener = listener;
        this.props = props;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public String id() { return id; }
    public SessionState state() { return state; }
    public long currentSeq() { return seq.get(); }
    public long pid() { return process.pid(); }

    /** 启动 stdout/stderr 读取线程。 */
    public void start() {
        Thread.ofVirtual().name("stdout-" + id).start(this::stdoutLoop);
        Thread.ofVirtual().name("stderr-" + id).start(this::stderrLoop);
    }

    private void stdoutLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                SessionEvent ev = parser.parse(seq.incrementAndGet(), line, "stdout");
                if (ev != null) {
                    publish(ev);
                }
            }
        } catch (IOException e) {
            log.debug("stdout 读取结束/中断: session={} err={}", id, e.getMessage());
        } finally {
            handleProcessExit();
        }
    }

    private void stderrLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                SessionEvent ev = parser.parse(seq.incrementAndGet(), line, "stderr");
                if (ev != null) {
                    publish(ev);
                }
            }
        } catch (IOException e) {
            log.debug("stderr 读取结束/中断: session={} err={}", id, e.getMessage());
        }
    }

    // ---------------- 事件流 ----------------

    private void publish(SessionEvent ev) {
        synchronized (ring) {
            ring.addLast(ev);
            while (ring.size() > props.getRingBuffer()) {
                ring.removeFirst();
            }
        }
        for (Consumer<SessionEvent> sub : subscribers) {
            try {
                sub.accept(ev);
            } catch (Exception e) {
                log.warn("会话订阅者抛异常: session={}", id, e);
            }
        }
        saver.offer(id, ev);
        dispatch(ev);
    }

    /**
     * 事件分派。核心语义（2026-08-30 spike 实测）：
     * <p>claude 在 print 模式下以“回合”为单位输出——每回合结束必然发一条 {@code result}，
     * 然后继续等 stdin。只有 stdin 关闭(EOF) 才会退出进程。</p>
     * <p>因此 result 永远不是会话终局：一律保留进程并转 WAITING_INPUT，等用户继续注入或
     * 显式 {@link #finish()}（关 stdin → 进程自然退出 → DONE/FAILED）。idleTimeout&gt;0 时
     * 长时间无操作会自动 finish。</p>
     * <ul>
     *   <li>permission_request → 中转授权；</li>
     *   <li>result → 记录摘要，转 WAITING_INPUT 保持连接；</li>
     *   <li>injectInput → 本地 publish 一条 user 事件（输入可见）。</li>
     * </ul>
     */
    private void dispatch(SessionEvent ev) {
        switch (ev.type()) {
            case "permission_request" -> {
                pendingPermissionRequestId = (String) ev.payload().get("requestId");
                transition(SessionState.WAITING_AUTH, "等待授权: " + ev.content());
            }
            case "result" -> {
                lastResultSuccess = !Boolean.TRUE.equals(ev.payload().get("isError"));
                summary = ev.content() == null ? "" : ev.content();
                transition(SessionState.WAITING_INPUT, "回合完成，可继续输入或结束会话");
                scheduleIdleEnd();
            }
            default -> { }
        }
    }

    /** 回合结束后（WAITING_INPUT 状态）空闲超时自动结束，防止进程/连接泄漏。 */
    private void scheduleIdleEnd() {
        long timeoutSec = props.getIdleTimeout();
        if (timeoutSec <= 0) {
            return;
        }
        Thread.ofVirtual().name("idle-" + id).start(() -> {
            while (process.isAlive() && state == SessionState.WAITING_INPUT) {
                long idleMs = System.currentTimeMillis() - lastActivityAt;
                if (idleMs >= timeoutSec * 1000) {
                    log.info("会话空闲超时({}s)，自动结束: session={}", timeoutSec, id);
                    finish();
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** 状态变更：写状态事件进流（订阅者+回放+落库），并回调监听器。 */
    public void transition(SessionState to, String reason) {
        synchronized (stateLock) {
            this.state = to;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", to.name());
        payload.put("reason", reason);
        SessionEvent ev = SessionEvent.of(seq.incrementAndGet(), "state", reason, "system", payload);
        publish(ev);
        listener.onStateChange(id, to, ev);
    }

    // ---------------- 交互 ----------------

    /** 注入用户消息（写 stdin JSONL）。格式必须与 claude stream-json 一致：完整 user message，而非 {"type":"input"}。 */
    public void injectInput(String text) {
        if (text == null || text.isBlank() || !process.isAlive()) {
            return;
        }
        lastActivityAt = System.currentTimeMillis();
        Map<String, Object> content = Map.of("type", "text", "text", text);
        Map<String, Object> message = Map.of("role", "user", "content", List.of(content));
        writeLine(toJson(Map.of("type", "user", "message", message)));
        // 本地 publish 一条 user 事件，保证"我输入了什么"在终端里可见（claude 回显的 user 事件解析时被跳过）
        publish(SessionEvent.of(seq.incrementAndGet(), "user", text, "system"));
        if (state == SessionState.WAITING_INPUT) {
            transition(SessionState.RUNNING, "收到用户输入，继续执行");
        }
    }

    /** 优雅结束：关闭 stdin（EOF），claude 读完后自然退出 → handleProcessExit → DONE/FAILED。 */
    public void finish() {
        if (!process.isAlive()) {
            return;
        }
        lastActivityAt = System.currentTimeMillis();
        closeStdin();
        // 若进程退得异常慢，兜底强杀，防止僵尸
        Thread.ofVirtual().name("finish-drain-" + id).start(() -> {
            try {
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("finish 后 20s 进程未退出，强杀: session={}", id);
                    ProcessHelper.killTree(process);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 授权响应（写 permission_result）。 */
    public void authorize(String requestId, boolean accepted, String scope) {
        lastActivityAt = System.currentTimeMillis();
        String rid = (requestId != null && !requestId.isBlank()) ? requestId : pendingPermissionRequestId;
        if (rid == null) {
            rid = "unknown";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "permission_result");
        payload.put("permission_request_id", rid);
        payload.put("permission", accepted ? "allow" : "deny");
        if (accepted && scope != null && !scope.isBlank()) {
            payload.put("scope", scope);
        }
        writeLine(toJson(payload));
        pendingPermissionRequestId = null;
        if (state == SessionState.WAITING_AUTH) {
            transition(SessionState.RUNNING, accepted ? "已授权，继续执行" : "已拒绝");
        }
    }

    /** 强杀：整树 kill，状态 TERMINATED。 */
    public void kill() {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        ProcessHelper.killTree(process);
        transition(SessionState.TERMINATED, "已手动终止");
        closeStdin();
    }

    /** 挂起：杀进程但保留 worktree（可 resume 续跑）。 */
    public void suspend() {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        ProcessHelper.killTree(process);
        transition(SessionState.SUSPENDED, "已挂起");
        closeStdin();
    }

    /** 订阅实时事件流，返回历史回放（环形缓冲快照）。 */
    public List<SessionEvent> subscribe(Consumer<SessionEvent> consumer) {
        subscribers.add(consumer);
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    public void unsubscribe(Consumer<SessionEvent> consumer) {
        subscribers.remove(consumer);
    }

    public void unsubscribeAll() {
        subscribers.clear();
    }

    public List<SessionEvent> replay() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    // ---------------- 生命周期 ----------------

    private void handleProcessExit() {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        closeStdin();
        int code;
        try {
            code = process.exitValue();
        } catch (Exception e) {
            code = -1;
        }
        boolean hasResult = !summary.isEmpty();
        boolean ok = hasResult ? lastResultSuccess : code == 0;
        transition(ok ? SessionState.DONE : SessionState.FAILED,
                ok ? "会话正常完成" : "会话异常退出 (exit=" + code + ")");
        listener.onExit(id, code, ok, summary);
    }

    private void writeLine(String json) {
        synchronized (stdinLock) {
            try {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            } catch (IOException e) {
                log.warn("写入 stdin 失败: session={} err={}", id, e.getMessage());
            }
        }
    }

    private void closeStdin() {
        synchronized (stdinLock) {
            try {
                stdin.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void close() {
        kill();
    }
}
