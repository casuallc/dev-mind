package com.devmind.agent.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * launch 异步化的受理登记（修复「等待 runner 确认超时/异常： null」——准备超过服务端
 * 15s launchAckTimeoutMs 所致）：handleLaunch 同步段只做纯帧校验（并发上限/必填字段/
 * 重复受理），通过即登记本表并立即回 {@code launched{ok:true}}；工作区准备
 * （clone ≤30min / fetch ≤5min）/上下文拉取/进程拉起移到虚拟线程，结果经 system 事件 +
 * exit 帧异步回报（服务端 {@code handleExit} 对零事件会话同样成立）。
 *
 * <p>受理到拉起之间的窗口由本类兜底：</p>
 * <ul>
 *   <li>input/authorize → stdin 行缓冲（拉起后按序补写；超上限丢最旧保最新）；</li>
 *   <li>finish → closeOnStart 标记（拉起补写后立即 EOF）；kill/suspend → cancelled 标记
 *   （准备线程在步骤边界检查并中止；注册后到达的 kill 走在册会话直杀，双通道不丢）；</li>
 *   <li>hello 对账/GC 存活清单/升级 busy 判定并入本表 id——准备中也属本节点持有，
 *   防服务端对账误判 FAILED、防 GC 收走半成品工作区、防升级拍死准备中的会话。</li>
 * </ul>
 *
 * <p>竞态收口：{@link #drain} 在 {@code sessions.register} 之后调用，出清与补写在
 * Pending 监视器内原子完成；{@link #bufferStdin} 拿到 Pending 后会二次确认仍在表内，
 * 否则重查转直写——缓冲/直写不会在注册窗口内乱序或丢失。</p>
 */
final class PendingLaunchRegistry {

    /** stdin 缓冲上限（准备窗口的输入多为连发消息；溢出丢最旧，防图片 base64 撑爆内存）。 */
    static final int MAX_BUFFERED_LINES = 50;

    /** 单个受理中的 launch。 */
    static final class Pending {
        final List<String> stdinBuffer = new ArrayList<>();
        boolean cancelled;
        boolean closeOnStart;
    }

    /** {@link #drain} 结果：拉起瞬间已被终止 / 拉起后需补 EOF。 */
    record Outcome(boolean cancelled, boolean closeOnStart) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** 受理登记；同 id 重复 launch 拒绝（旧行为静默覆盖在册会话，排查极痛）。 */
    void begin(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("launch 帧缺少 sessionId");
        }
        if (pending.putIfAbsent(sessionId, new Pending()) != null) {
            throw new IllegalStateException("会话 " + sessionId + " 已在准备中（重复 launch）");
        }
    }

    boolean isPending(String sessionId) {
        return pending.containsKey(sessionId);
    }

    int size() {
        return pending.size();
    }

    /** hello 对账/GC 存活清单并入用（准备中也属本节点持有）。 */
    Set<String> ids() {
        return Set.copyOf(pending.keySet());
    }

    /**
     * input/authorize 帧路由：准备中 → 缓冲并返回 true；非准备中 → false（调用方转
     * 在册会话直写）。注册窗口（已 register 未 drain）内仍缓冲，由 drain 按序补写，保序。
     */
    boolean bufferStdin(String sessionId, String line) {
        for (;;) {
            Pending p = pending.get(sessionId);
            if (p == null) {
                return false;
            }
            synchronized (p) {
                if (pending.get(sessionId) != p) {
                    continue; // 已出清（drain 进行中/完成），重查转直写
                }
                if (p.stdinBuffer.size() >= MAX_BUFFERED_LINES) {
                    p.stdinBuffer.remove(0);
                }
                p.stdinBuffer.add(line);
                return true;
            }
        }
    }

    /** finish 帧路由：准备中 → 标记拉起后补 EOF（返回 true）；非准备中 → false 转在册直关。 */
    boolean markCloseOnStart(String sessionId) {
        Pending p = pending.get(sessionId);
        if (p == null) {
            return false;
        }
        synchronized (p) {
            p.closeOnStart = true;
        }
        return true;
    }

    /** kill/suspend 帧路由：准备中 → 标记中止（准备线程步骤边界检查；返回 true）。 */
    boolean cancel(String sessionId) {
        Pending p = pending.get(sessionId);
        if (p == null) {
            return false;
        }
        synchronized (p) {
            p.cancelled = true;
        }
        return true;
    }

    /** 准备线程步骤边界的取消检查（长 git 操作无法中断，只能在边界中止）。 */
    boolean isCancelled(String sessionId) {
        Pending p = pending.get(sessionId);
        if (p == null) {
            return true; // 已被移除（异常出清），按取消处理不再继续
        }
        synchronized (p) {
            return p.cancelled;
        }
    }

    /**
     * 拉起成功后的出清 + 按序补写缓冲 stdin（必须在 {@code sessions.register} 之后调用）。
     * 返回 null = 不在准备中（异常路径已被 remove）；否则携 cancelled/closeOnStart 标记——
     * cancelled=true 时调用方应立即 kill 刚注册的进程（kill 帧先于注册到达的窗口）。
     */
    Outcome drain(String sessionId, Consumer<String> stdinWriter) {
        for (;;) {
            Pending p = pending.get(sessionId);
            if (p == null) {
                return null;
            }
            synchronized (p) {
                if (pending.remove(sessionId, p)) {
                    p.stdinBuffer.forEach(stdinWriter);
                    return new Outcome(p.cancelled, p.closeOnStart);
                }
                // 并发 remove（失败路径）胜出，重查
            }
        }
    }

    /** 准备失败/中止的出清（缓冲随对象丢弃——会话不会拉起，补写无意义）。 */
    void remove(String sessionId) {
        pending.remove(sessionId);
    }
}
