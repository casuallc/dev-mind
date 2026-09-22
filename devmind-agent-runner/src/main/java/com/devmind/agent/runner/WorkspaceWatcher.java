package com.devmind.agent.runner;

import com.devmind.common.agent.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * CAP-54 工作区变更触发器：事件驱动 + 去抖 + 哈希去重 + 兜底轮询，产出
 * {@code workspace_status} 上行帧（git 状态快照由 {@link GitStatusCollector} 采集）。
 *
 * <p><b>触发源</b>（挂 {@link RunnerSessionRegistry.EventTap}，事件上行后回调）：</p>
 * <ul>
 *   <li>{@code tool_result} 且对应工具 ∈ {@link #MUTATING_TOOLS}（tool_use 时按 toolUseId
 *       记下工具名，tool_result 时查表）——claude 改文件的全部入口，事件驱动零监听开销；</li>
 *   <li>{@code result}（一轮结束）——强制刷一次，保证停顿态面板是最新的；</li>
 *   <li>兜底：活跃会话每 {@link #POLL_MS} 慢轮询（防 Bash 起后台进程写文件等绕过工具事件的缝隙）。</li>
 * </ul>
 *
 * <p><b>去抖/去重</b>：触发后 {@link #DEBOUNCE_MS} 合并成一轮采集（连续 Edit 风暴只采一次）；
 * 快照哈希（不含 ts）与上次一致不推——兜底轮询与高频事件都不会刷屏。</p>
 *
 * <p>采集在虚拟线程执行（git 命令最长秒级，调度线程只做计时）；帧经 CAP-50 出口队列
 * 顺序落线。会话消失（进程退出）时 watch 状态在下一轮兜底轮询回收。</p>
 */
public class WorkspaceWatcher implements RunnerSessionRegistry.EventTap {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceWatcher.class);

    /** 会改文件的工具（tool_result 触发采集的白名单） */
    static final Set<String> MUTATING_TOOLS = Set.of("Edit", "MultiEdit", "Write", "NotebookEdit", "Bash");
    /** 连续修改合并窗口 */
    static final long DEBOUNCE_MS = 500;
    /** 兜底轮询周期（也是 watch 状态回收周期） */
    static final long POLL_MS = 15_000;

    private final RunnerSessionRegistry sessions;
    private final Consumer<Map<String, Object>> sender;
    private final GitStatusCollector collector;
    private final ScheduledExecutorService scheduler;

    private final Map<String, Watch> watches = new ConcurrentHashMap<>();

    public WorkspaceWatcher(RunnerSessionRegistry sessions, Consumer<Map<String, Object>> sender) {
        this(sessions, sender, new GitStatusCollector());
    }

    /** 测试用：可替换采集器。 */
    WorkspaceWatcher(RunnerSessionRegistry sessions, Consumer<Map<String, Object>> sender,
                     GitStatusCollector collector) {
        this.sessions = sessions;
        this.sender = sender;
        this.collector = collector;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "workspace-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动兜底轮询（runner main 装配后调用一次）。 */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::pollAll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** launch 成功后调用：尽快推一份初始快照（面板立即可见「干净工作区」基线）。 */
    public void onLaunch(String sessionId) {
        schedule(sessionId, DEBOUNCE_MS);
    }

    @Override
    public void onEvent(String sessionId, SessionEvent ev) {
        Watch w = watches.computeIfAbsent(sessionId, k -> new Watch());
        switch (ev.type()) {
            case "tool_use" -> {
                Object id = ev.payload() == null ? null : ev.payload().get("toolUseId");
                if (id instanceof String s && !s.isEmpty()) {
                    w.toolNames.put(s, ev.content()); // tool_use 的 content = 工具名
                }
            }
            case "tool_result" -> {
                Object id = ev.payload() == null ? null : ev.payload().get("toolUseId");
                String name = id instanceof String s ? w.toolNames.remove(s) : null;
                if (name != null && MUTATING_TOOLS.contains(name)) {
                    schedule(sessionId, DEBOUNCE_MS);
                }
            }
            case "result" -> schedule(sessionId, DEBOUNCE_MS); // 一轮结束强制刷
            default -> { }
        }
    }

    /** 去抖调度：取消上次未执行的采集，重新计时。 */
    private void schedule(String sessionId, long delayMs) {
        Watch w = watches.computeIfAbsent(sessionId, k -> new Watch());
        synchronized (w) {
            if (w.pending != null) {
                w.pending.cancel(false);
            }
            w.pending = scheduler.schedule(
                    () -> Thread.ofVirtual().name("ws-collect-" + sessionId)
                            .start(() -> collectAndPush(sessionId)),
                    delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 兜底轮询：活跃会话各采一次（哈希去重防刷屏），同时回收已消失会话的 watch 状态。 */
    private void pollAll() {
        try {
            java.util.Set<String> active = Set.copyOf(sessions.activeSessionIds());
            watches.keySet().retainAll(active);
            for (String sid : active) {
                collectAndPush(sid);
            }
        } catch (Exception e) {
            log.debug("工作区兜底轮询异常: {}", e.getMessage());
        }
    }

    /** 采集并推送（快照哈希与上次一致不推；会话不在本节点运行跳过）。 */
    void collectAndPush(String sessionId) {
        var dir = sessions.sessionDirOf(sessionId);
        if (dir.isEmpty()) {
            return;
        }
        collectAndPush(sessionId, dir.get());
    }

    /** 采集并推送（显式目录；workspace_query 的 status action 复用）。 */
    Map<String, Object> collectAndPush(String sessionId, Path codeDir) {
        Map<String, Object> snapshot = collector.collect(codeDir);
        Object ts = snapshot.remove("ts"); // ts 每轮都变，不参与去重哈希
        Watch w = watches.computeIfAbsent(sessionId, k -> new Watch());
        int hash = snapshot.hashCode();
        if (hash == w.lastHash) {
            snapshot.put("ts", ts);
            return snapshot;
        }
        w.lastHash = hash;
        snapshot.put("ts", ts);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "workspace_status");
        frame.put("sessionId", sessionId);
        frame.put("snapshot", snapshot);
        try {
            sender.accept(frame);
        } catch (Exception e) {
            log.debug("workspace_status 发送失败: session={} err={}", sessionId, e.getMessage());
        }
        return snapshot;
    }

    /** runner 关闭时停调度（shutdown hook 链路上调用）。 */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 每会话的 watch 状态。 */
    private static final class Watch {
        /** toolUseId → 工具名（tool_use 记下，tool_result 查后移除；上限由会话长度自然约束） */
        final Map<String, String> toolNames = new ConcurrentHashMap<>();
        /** 去抖 pending（schedule 内 synchronized 保护） */
        ScheduledFuture<?> pending;
        /** 上次推送的快照哈希（不含 ts） */
        int lastHash;
    }
}
