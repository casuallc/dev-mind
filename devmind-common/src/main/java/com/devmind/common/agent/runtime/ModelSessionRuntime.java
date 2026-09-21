package com.devmind.common.agent.runtime;

import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.model.ModelCallException;
import com.devmind.common.model.ModelInterruptedException;
import com.devmind.common.model.OpenAiCompatChatStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-49 模型执行体运行时：一轮问答 = 一次 SSE 流，产出的<b>事件模型与
 * {@link RemoteSessionRuntime} 完全一致</b>（前端/落库/通知/状态机全部复用，协议零变更）。
 *
 * <p>与另外两个实现的根本差异：<b>没有进程、没有节点，只有一次出站 HTTP</b>。因此
 * {@link #alive()} 的含义从"进程还活着"变成"会话尚未收口"——{@link #finish()} 收口即结束，
 * 没有 EOF 可等；{@link #doFinish()} 也从"关 stdin"变成"取消流 + 收口"。</p>
 *
 * <h2>事件次序（每轮）</h2>
 * <pre>
 * user ─▶ state:RUNNING ─▶ text_delta×N（节流合并）─▶ assistant（全量，source=model）
 *      ─▶ result{isError,durationMs,subtype,truncated?}
 * </pre>
 *
 * <h2>几处不能想当然的地方</h2>
 * <ul>
 *   <li><b>必须重写 {@link #injectInput}</b>：内核次序是 {@code sendUserMessage → publish(user) →
 *       transition(RUNNING)}，在 sendUserMessage 里起生成线程会让首批 {@code text_delta} 排到
 *       {@code user} 事件之前。这里改成"先记事件、再起线程"，并把 user 事件的 seq 交给
 *       {@link TurnSupplier}，历史装配据此把本轮提问排除在外。</li>
 *   <li><b>失败后不 {@code handleExit}</b>：端点 401/超时是"这一轮没成"，不是"会话结束"——
 *       落 {@code error} + {@code result{isError:true}} 后回到 WAITING_INPUT，让用户改完再问。
 *       模型会话是"只有人让它结束"的会话。</li>
 *   <li><b>{@code kill} 之后不再落 {@code result}</b>：内核 {@code dispatch(result)} 无条件转
 *       WAITING_INPUT，会把刚设的 TERMINATED 覆盖回去（实时态与 DB 持久态劈叉）。</li>
 *   <li><b>增量必须节流合并</b>：一 token 一事件 ≈ 每轮上千行 {@code chat_events}；合并只在
 *       运行时侧做（放到落库出口做会让 WS 同时失去增量）。</li>
 * </ul>
 */
public class ModelSessionRuntime extends AbstractSessionRuntime {

    private static final Logger log = LoggerFactory.getLogger(ModelSessionRuntime.class);

    /** 事件来源标记：前端/排障据此区分"模型直连产出"与 CLI 产出 */
    public static final String SOURCE = "model";

    /**
     * 端点调用参数（解析在能力模块完成：本类不认识 JPA/端点表，只拿现成的传输参数）。
     *
     * @param apiKey 解密后的明文密钥，禁落日志/禁进异常消息
     */
    public record ModelTarget(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    /**
     * 多轮装配（能力模块实现，common 不认识 JPA/知识库）：system + 历史 + 本轮 user。
     *
     * @param userText  本轮用户文本（可能已带 {@code <knowledge-context>} 前缀）
     * @param beforeSeq 本轮 user 事件的 seq——历史只取它之前的，否则本轮提问会在历史里出现两次
     */
    public interface TurnSupplier {
        List<OpenAiCompatChatStream.Message> buildTurn(String userText, long beforeSeq);
    }

    /**
     * 流式参数。
     *
     * @param flushMs        增量合并窗口（毫秒）：攒够时间或字数才发一条 text_delta
     * @param flushChars     增量合并字数阈值（与窗口先到先发）
     * @param answerMaxChars 单轮正文上限：超出即截断并在 result 里标 truncated
     */
    public record StreamTuning(long flushMs, int flushChars, int answerMaxChars) {

        public static StreamTuning defaults() {
            return new StreamTuning(120, 24, 100_000);
        }
    }

    private final ModelTarget target;
    private final TurnSupplier turns;
    private final StreamTuning tuning;
    private final OpenAiCompatChatStream.Options options;

    /** 生成中标志（容量分账与并发注入保护都看它；生命周期由 turnLock 串起来） */
    private final Object turnLock = new Object();
    private boolean generating;
    /** finish 请求：回合线程收尾时负责收口（保证"已产出的部分正文"先落进事件流） */
    private boolean closingRequested;
    private volatile Thread turnThread;

    private final Object deltaLock = new Object();
    private final StringBuilder pendingDelta = new StringBuilder();
    /**
     * 本轮已产出的正文（原始未合并）。攒它是因为<b>中断时客户端不返回正文</b>——
     * 它抛 {@link ModelInterruptedException} 就退出了，已吐出的部分只在增量里，
     * 不自己攒就只能把用户已经看到的那段回答丢掉。
     */
    private final StringBuilder turnAnswer = new StringBuilder();
    private long lastFlushAt = System.currentTimeMillis();

    public ModelSessionRuntime(String id, ModelTarget target, TurnSupplier turns, StreamTuning tuning,
                               RuntimeEventSink sink, RuntimeListener listener, RuntimeSettings settings) {
        super(id, sink, listener, settings);
        this.target = target;
        this.turns = turns;
        this.tuning = tuning;
        this.options = OpenAiCompatChatStream.Options.of(
                target.baseUrl(), target.apiKey(), target.model(), target.timeoutSeconds());
    }

    /** 是否正在生成（容量分账只数它：模型会话空闲时不占任何外部资源） */
    public boolean generating() {
        synchronized (turnLock) {
            return generating;
        }
    }

    public String modelName() {
        return target.model();
    }

    // ---------------- 交互 ----------------

    /**
     * 注入用户消息并起一轮生成。<b>本方法整体重写内核实现</b>（见类注释：事件次序与 seq 捕获）。
     *
     * <p>图片直接拒：模型执行体没有图片能力，静默丢弃是更坏的失败（用户以为模型看到了图）。</p>
     */
    @Override
    public void injectInput(String text, List<InputImage> images) {
        if (images != null && !images.isEmpty()) {
            rejectWithError("模型执行体不支持图片输入：请去掉图片，或改用智能体（Agent）执行体");
            return;
        }
        boolean hasText = text != null && !text.isBlank();
        if (!hasText || !alive()) {
            return;
        }
        if (generating()) {
            rejectWithError("上一轮回答还在生成中：请先点「停止生成」再提问");
            return;
        }
        touchActivity();
        long userSeq = nextSeq();
        publish(SessionEvent.of(userSeq, "user", text, "system"));
        transition(SessionState.RUNNING, "收到用户输入，开始生成");
        startTurn(text, userSeq);
    }

    /** 创建会话时的首轮提问（与 {@link #injectInput} 同一条路：新运行时状态即 RUNNING、alive 即为真）。 */
    public void startFirstTurn(String text) {
        injectInput(text, List.of());
    }

    /**
     * 中断当前生成（「停止生成」）。保留会话与已产出的部分正文，随后可继续提问。
     *
     * @return false = 当前没有在生成的回合（调用方按 409 处理）
     */
    public boolean interruptTurn() {
        synchronized (turnLock) {
            if (!generating) {
                return false;
            }
        }
        cancelTurn();
        return true;
    }

    // ---------------- 回合线程 ----------------

    private void startTurn(String userText, long userSeq) {
        synchronized (turnLock) {
            // 与 finish/kill 抢跑：收口已发生就别起线程（否则事件会落在已终局的会话上）
            if (exitHandled.get() || !alive()) {
                return;
            }
            generating = true;
            closingRequested = false;
        }
        synchronized (deltaLock) {
            turnAnswer.setLength(0);
            pendingDelta.setLength(0);
            lastFlushAt = System.currentTimeMillis();
        }
        Thread t = Thread.ofVirtual().name("model-turn-" + id).start(() -> runTurn(userText, userSeq));
        turnThread = t;
    }

    private void runTurn(String userText, long userSeq) {
        long startedAt = System.currentTimeMillis();
        OpenAiCompatChatStream.Reply reply = null;
        ModelCallException failure = null;
        try {
            List<OpenAiCompatChatStream.Message> messages = turns.buildTurn(userText, userSeq);
            reply = OpenAiCompatChatStream.chatStream(options, messages, this::onDelta);
        } catch (ModelInterruptedException e) {
            // 中断（用户点停止 / finish 收口）：不是故障，保留已产出的部分正文
            log.info("模型问答生成被中断: chat={} 已产出={}字", id, turnLength());
        } catch (ModelCallException e) {
            failure = e;
            log.warn("模型问答生成失败: chat={} err={}", id, e.getMessage());
        } catch (Exception e) {
            failure = new ModelCallException("模型问答回合异常: " + e, e);
            log.warn("模型问答回合异常: chat={}", id, e);
        }
        finishTurn(reply, failure, startedAt);
    }

    /** 增量回调（在读取线程上，必须快速返回）：攒够字数或窗口就发一条合并后的 text_delta。 */
    private void onDelta(String piece) {
        synchronized (deltaLock) {
            turnAnswer.append(piece);
            pendingDelta.append(piece);
            if (pendingDelta.length() >= tuning.flushChars()
                    || System.currentTimeMillis() - lastFlushAt >= tuning.flushMs()) {
                flushDelta();
            }
        }
    }

    /** 发布并清空积压增量（调用方须持有 deltaLock）。 */
    private void flushDelta() {
        if (pendingDelta.length() == 0) {
            lastFlushAt = System.currentTimeMillis();
            return;
        }
        String text = pendingDelta.toString();
        pendingDelta.setLength(0);
        lastFlushAt = System.currentTimeMillis();
        if (exitHandled.get()) {
            return;                                   // 强杀后不再往流里写
        }
        publish(SessionEvent.of(nextSeq(), "text_delta", text, SOURCE));
    }

    private int turnLength() {
        synchronized (deltaLock) {
            return turnAnswer.length();
        }
    }

    /** 回合收尾：先清积压增量，再落全量 assistant 与 result；finish 请求的收口也在这里完成。 */
    private void finishTurn(OpenAiCompatChatStream.Reply reply, ModelCallException failure, long startedAt) {
        // 中断后中断位可能是置着的（JDK 在读被中断时会重新置位）——先清掉，别污染落库/广播
        Thread.interrupted();
        boolean killed = exitHandled.get();
        String text;
        synchronized (deltaLock) {
            if (!killed) {
                flushDelta();
            }
            text = turnAnswer.toString();
        }
        boolean closeNow;
        synchronized (turnLock) {
            generating = false;
            closeNow = closingRequested;
            closingRequested = false;
        }
        turnThread = null;
        if (killed) {
            return;                                   // 强杀：本回合作废（见类注释）
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        boolean truncated = reply != null && reply.truncated();
        boolean interrupted = reply == null && failure == null;

        if (failure != null) {
            publish(SessionEvent.of(nextSeq(), "error", failure.getMessage(), SOURCE));
        } else if (!text.isBlank()) {
            int cap = Math.min(settings.maxEventBytes(), tuning.answerMaxChars());
            if (text.length() > cap) {
                text = text.substring(0, cap);
                truncated = true;
                publish(SessionEvent.of(nextSeq(), "log",
                        "回答过长（超过 " + cap + " 字符），仅保留前 " + cap + " 字符", "system"));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", target.model());
            if (truncated) {
                payload.put("truncated", true);
            }
            publish(SessionEvent.of(nextSeq(), "assistant", text, SOURCE, payload));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("isError", failure != null);
        payload.put("durationMs", durationMs);
        payload.put("model", target.model());
        payload.put("subtype", failure != null ? "error" : (interrupted ? "interrupted" : "success"));
        if (truncated) {
            payload.put("truncated", true);
        }
        publish(SessionEvent.of(nextSeq(), "result", summaryOf(failure, text, truncated), SOURCE, payload));

        if (closeNow) {
            handleExit(0);                            // finish 收口（回合线程负责，保证部分正文已落流）
        }
    }

    /** result 内容 = 摘要（内核会把它当会话 summary 落库并显示在列表里）。 */
    private static String summaryOf(ModelCallException failure, String text, boolean truncated) {
        if (failure != null) {
            return failure.getMessage();
        }
        if (text.isBlank()) {
            return "已中断（未产出内容）";
        }
        String one = text.replace('\n', ' ').replace('\r', ' ').strip();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    /** 拒绝注入：落一条 error 事件（会话状态不变），让用户知道为什么没反应。 */
    private void rejectWithError(String message) {
        publish(SessionEvent.of(nextSeq(), "error", message, "system"));
    }

    private void cancelTurn() {
        Thread t = turnThread;
        if (t != null) {
            t.interrupt();                            // 中断读线程 = 取消请求 + 关流（见 OpenAiCompatChatStream）
        }
    }

    // ---------------- 内核钩子 ----------------

    /** 不做任何事：本实现自己起生成线程（见类注释的 injectInput 次序） */
    @Override
    protected void sendUserMessage(String text, List<InputImage> images) {
    }

    /** 模型执行体无授权概念（能力层对 MODEL 会话的 authorize 直接 409） */
    @Override
    protected void sendPermissionResult(String requestId, boolean accepted, String scope) {
    }

    /**
     * 优雅结束：取消在跑的流并收口。
     *
     * <p>没有在生成 → 立即 {@link #handleExit(int)}（空闲中的会话结束就是结束）；
     * 有在生成 → 交给回合线程收口——它会先把已产出的部分正文落进事件流再收口，
     * 直接在这里收口会把用户已经看到的那段回答丢掉。</p>
     */
    @Override
    protected void doFinish() {
        boolean idle;
        synchronized (turnLock) {
            idle = !generating;
            if (!idle) {
                closingRequested = true;
            }
        }
        cancelTurn();
        if (idle) {
            handleExit(0);
        }
    }

    /** kill/suspend：中断在跑的流（回合线程会看到 exitHandled 并作废本回合） */
    @Override
    protected void destroyTree() {
        cancelTurn();
    }

    @Override
    protected boolean alive() {
        return !exitHandled.get();
    }
}
