package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 会话运行时的共享内核：环形缓冲、订阅者、事件落库、状态机与交互语义。
 * 本地（{@link SessionRuntime}，持有子进程）与远程（{@link RemoteSessionRuntime}，
 * 经节点连接发指令）只差 5 个钩子：{@link #sendUserMessage} / {@link #sendPermissionResult} /
 * {@link #doFinish} / {@link #destroyTree} / {@link #alive}。
 *
 * <p>核心语义（2026-08-30 spike 实测）：claude print 模式按回合输出，每回合结束发一条
 * {@code result} 然后继续等 stdin，只有 stdin 关闭(EOF) 才退出进程。因此 result 永远不是
 * 终局：一律转 WAITING_INPUT 保持连接，等用户继续注入或显式 {@link #finish()}。</p>
 */
public abstract class AbstractSessionRuntime implements SessionHandle {

    private static final Logger log = LoggerFactory.getLogger(AbstractSessionRuntime.class);

    protected final String id;
    protected final SessionEventSaver saver;
    protected final RuntimeListener listener;
    protected final SessionProperties props;

    private final Object stateLock = new Object();
    private final ArrayDeque<SessionEvent> ring = new ArrayDeque<>();
    private final Set<Consumer<SessionEvent>> subscribers = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();
    protected final AtomicBoolean exitHandled = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.RUNNING;
    private volatile boolean lastResultSuccess = false;
    private volatile String summary = "";
    private volatile String pendingPermissionRequestId;
    private volatile long lastActivityAt = System.currentTimeMillis();

    protected AbstractSessionRuntime(String id, SessionEventSaver saver, RuntimeListener listener,
                                     SessionProperties props) {
        this.id = id;
        this.saver = saver;
        this.listener = listener;
        this.props = props;
    }

    // ---------------- 平台差异钩子 ----------------

    /** 写一条 user message 到 agent stdin（本地=管道 JSONL；远程=节点指令）。 */
    protected abstract void sendUserMessage(String text);

    /** 写 permission_result 到 agent stdin。 */
    protected abstract void sendPermissionResult(String requestId, boolean accepted, String scope);

    /** 优雅结束动作（本地=关 stdin EOF；远程=发 finish 指令）。 */
    protected abstract void doFinish();

    /** 强杀进程树（远程=发 kill/suspend 指令，尽力而为）。 */
    protected abstract void destroyTree();

    /** 会话是否存活（本地=process.isAlive；远程=未收到 exit 且节点在线）。 */
    protected abstract boolean alive();

    /** kill/suspend/handleExit 后的收尾（本地关 stdin writer；远程默认无事可做）。 */
    protected void cleanupStdin() {
    }

    // ---------------- SessionHandle ----------------

    @Override
    public String id() { return id; }

    @Override
    public SessionState state() { return state; }

    @Override
    public long currentSeq() { return seq.get(); }

    /** 下一服务端序号（远程事件重编 seq：单连接有序到达，runner seq 不带上行）。 */
    protected long nextSeq() { return seq.incrementAndGet(); }

    // ---------------- 事件流 ----------------

    /** 事件入流：环形缓冲 + 订阅者广播 + 落库 + 状态机分派。 */
    protected void publish(SessionEvent ev) {
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
     * 事件分派（本地/远程同语义，远程事件已由 runner 解析成同一模型）：
     * permission_request → 中转授权；result → 记录摘要转 WAITING_INPUT。
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
            while (alive() && state == SessionState.WAITING_INPUT) {
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
        SessionEvent ev = SessionEvent.of(nextSeq(), "state", reason, "system", payload);
        publish(ev);
        listener.onStateChange(id, to, ev);
    }

    // ---------------- 交互 ----------------

    /** 注入用户消息；本地 publish 一条 user 事件保证"我输入了什么"在终端可见（agent 回显解析时被跳过）。 */
    @Override
    public void injectInput(String text) {
        if (text == null || text.isBlank() || !alive()) {
            return;
        }
        lastActivityAt = System.currentTimeMillis();
        sendUserMessage(text);
        publish(SessionEvent.of(nextSeq(), "user", text, "system"));
        if (state == SessionState.WAITING_INPUT) {
            transition(SessionState.RUNNING, "收到用户输入，继续执行");
        }
    }

    /** 优雅结束：关 stdin（EOF）/发 finish 指令，agent 读完后自然退出 → DONE/FAILED；20s 不退兜底强杀。 */
    @Override
    public void finish() {
        if (!alive()) {
            return;
        }
        lastActivityAt = System.currentTimeMillis();
        doFinish();
        Thread.ofVirtual().name("finish-drain-" + id).start(() -> {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
            while (alive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (alive() && !exitHandled.get()) {
                log.warn("finish 后 20s 会话未退出，强杀: session={}", id);
                destroyTree();
            }
        });
    }

    /** 授权响应。 */
    @Override
    public void authorize(String requestId, boolean accepted, String scope) {
        lastActivityAt = System.currentTimeMillis();
        String rid = (requestId != null && !requestId.isBlank()) ? requestId : pendingPermissionRequestId;
        if (rid == null) {
            rid = "unknown";
        }
        sendPermissionResult(rid, accepted, scope);
        pendingPermissionRequestId = null;
        if (state == SessionState.WAITING_AUTH) {
            transition(SessionState.RUNNING, accepted ? "已授权，继续执行" : "已拒绝");
        }
    }

    /** 强杀：整树 kill，状态 TERMINATED。 */
    @Override
    public void kill() {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        destroyTree();
        transition(SessionState.TERMINATED, "已手动终止");
        cleanupStdin();
    }

    /** 挂起：杀进程但保留 worktree（可 resume 续跑）。 */
    @Override
    public void suspend() {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        destroyTree();
        transition(SessionState.SUSPENDED, "已挂起");
        cleanupStdin();
    }

    /** 订阅实时事件流，返回历史回放（环形缓冲快照）。 */
    @Override
    public List<SessionEvent> subscribe(Consumer<SessionEvent> consumer) {
        subscribers.add(consumer);
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    @Override
    public void unsubscribe(Consumer<SessionEvent> consumer) {
        subscribers.remove(consumer);
    }

    @Override
    public void unsubscribeAll() {
        subscribers.clear();
    }

    @Override
    public List<SessionEvent> replay() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    // ---------------- 生命周期 ----------------

    /** 进程/远端会话退出收口：DONE/FAILED 口径 = 有 result 看 isError，否则看退出码。 */
    public void handleExit(int code) {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        cleanupStdin();
        boolean hasResult = !summary.isEmpty();
        boolean ok = hasResult ? lastResultSuccess : code == 0;
        transition(ok ? SessionState.DONE : SessionState.FAILED,
                ok ? "会话正常完成" : "会话异常退出 (exit=" + code + ")");
        listener.onExit(id, code, ok, summary);
    }

    @Override
    public void close() {
        kill();
    }
}
