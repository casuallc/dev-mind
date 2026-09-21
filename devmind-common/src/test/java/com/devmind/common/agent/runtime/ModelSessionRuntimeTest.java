package com.devmind.common.agent.runtime;

import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.SessionEvent;
import com.devmind.common.model.OpenAiCompatChatStream;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-49 模型执行体运行时：真起 SSE 假端点，钉死"事件模型与远程运行时一致"这件事——
 * 次序、节流、失败不判死、kill 后不再写流、中断保留会话与部分正文。
 */
class ModelSessionRuntimeTest {

    private static final String KEY = "sk-secret-key-123";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Long> beforeSeqs = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------- 假端点 ----------------

    private String serveSse(List<String> chunks, long holdAfterFirstMs) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            try {
                calls.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                for (int i = 0; i < chunks.size(); i++) {
                    out.write(chunks.get(i).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (i == 0 && holdAfterFirstMs > 0) {
                        Thread.sleep(holdAfterFirstMs);
                    }
                }
            } catch (Exception ignored) {
                // 中断/看门狗收口都会让写失败
            } finally {
                closeQuietly(ex);
            }
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    /** 非流式/错误码响应（测失败路径：这一轮没成，但会话不该被判死） */
    private String serveOnce(int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            try {
                calls.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // ignore
            } finally {
                closeQuietly(ex);
            }
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    private static void closeQuietly(HttpExchange ex) {
        try {
            ex.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static String content(String text) {
        return "data: {\"model\":\"m1\",\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    private static final String DONE = "data: [DONE]\n\n";

    // ---------------- 测试夹具 ----------------

    /** 记录事件 + 状态变更 + 退出回调的观察者 */
    private final List<SessionEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final List<SessionState> states = new CopyOnWriteArrayList<>();
    private final List<Boolean> exits = new CopyOnWriteArrayList<>();

    private final RuntimeEventSink sink = (sessionId, ev) -> { };

    private final RuntimeListener listener = new RuntimeListener() {
        @Override
        public void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent) {
            states.add(state);
        }

        @Override
        public void onExit(String sessionId, int exitCode, boolean success, String summary) {
            exits.add(success);
        }
    };

    /** 装配固定 messages 的 TurnSupplier（记录收到的 beforeSeq 供断言） */
    private ModelSessionRuntime runtime(String baseUrl, ModelSessionRuntime.StreamTuning tuning) {
        ModelSessionRuntime rt = new ModelSessionRuntime("chat1",
                new ModelSessionRuntime.ModelTarget(baseUrl, KEY, "m1", 5),
                (userText, beforeSeq) -> {
                    beforeSeqs.add(beforeSeq);
                    return List.of(OpenAiCompatChatStream.Message.system("SYS"),
                            OpenAiCompatChatStream.Message.user(userText));
                },
                tuning, sink, listener, RuntimeSettings.defaults());
        rt.subscribe(events::add);
        return rt;
    }

    private static ModelSessionRuntime.StreamTuning tuning(long flushMs, int flushChars, int maxChars) {
        return new ModelSessionRuntime.StreamTuning(flushMs, flushChars, maxChars);
    }

    private SessionEvent awaitType(String type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (events) {
                for (SessionEvent ev : events) {
                    if (type.equals(ev.type())) {
                        return ev;
                    }
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    private List<SessionEvent> ofType(String type) {
        synchronized (events) {
            return events.stream().filter(e -> type.equals(e.type())).toList();
        }
    }

    /** 等状态落定：result 事件入流早于内核 dispatch 转状态，别在事件到的瞬间断言 state */
    private static void awaitState(ModelSessionRuntime rt, SessionState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (rt.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    // ---------------- 正常回合 ----------------

    @Test
    void oneTurnProducesTheRemoteEventSequence() throws Exception {
        String baseUrl = serveSse(List.of(content("你好"), content("，世界"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));

        rt.startFirstTurn("在吗");

        assertNotNull(awaitType("result", 5000));
        awaitState(rt, SessionState.WAITING_INPUT);
        assertEquals(List.of("user", "state", "text_delta", "assistant", "result", "state"),
                events.stream().map(SessionEvent::type).toList(),
                "次序与前端/落库约定一致：先记用户提问，再起回合，全量 assistant 与 result，"
                        + "末条 state 是内核 dispatch(result) 转 WAITING_INPUT 写的");
        assertEquals(SessionState.WAITING_INPUT, rt.state(), "回合结束转 WAITING_INPUT（可继续提问），不是终态");
        SessionEvent assistant = ofType("assistant").get(0);
        assertEquals("你好，世界", assistant.content());
        assertEquals(ModelSessionRuntime.SOURCE, assistant.source());
        assertEquals("m1", assistant.payload().get("model"));
        SessionEvent result = ofType("result").get(0);
        assertEquals(false, result.payload().get("isError"));
        assertEquals("success", result.payload().get("subtype"));
        assertNotNull(result.payload().get("durationMs"));
        assertEquals(1, calls.get());
    }

    @Test
    void historyIsBuiltBeforeTheCurrentUserEvent() throws Exception {
        String baseUrl = serveSse(List.of(content("答"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));

        rt.startFirstTurn("第一问");

        assertNotNull(awaitType("result", 5000));
        SessionEvent user = ofType("user").get(0);
        assertEquals(List.of(user.seq()), beforeSeqs,
                "装配用 beforeSeq 必须是本轮 user 事件的 seq——否则本轮提问会在历史里出现两次");
    }

    @Test
    void deltasAreMergedInsteadOfOneEventPerToken() throws Exception {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add(content(String.valueOf(i)));
        }
        chunks.add(DONE);
        String baseUrl = serveSse(chunks, 0);
        // 窗口给足（10s）只看字数阈值：每 4 字一条
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 4, 100_000));

        rt.startFirstTurn("数一下");

        assertNotNull(awaitType("result", 5000));
        List<SessionEvent> deltas = ofType("text_delta");
        assertEquals("0123456789", deltas.stream().map(SessionEvent::content).reduce("", String::concat));
        assertTrue(deltas.size() < 10,
                "一 token 一事件会撑爆 chat_events（每轮上千行），必须合并: " + deltas.size());
        assertEquals("0123456789", ofType("assistant").get(0).content());
    }

    @Test
    void longAnswerIsTruncatedAndFlagged() throws Exception {
        String baseUrl = serveSse(List.of(content("0123456789"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 5));

        rt.startFirstTurn("写长点");

        SessionEvent result = awaitType("result", 5000);
        assertNotNull(result);
        assertEquals("01234", ofType("assistant").get(0).content(), "超出上限即截断，不静默");
        assertEquals(true, ofType("assistant").get(0).payload().get("truncated"));
        assertEquals(true, result.payload().get("truncated"), "截断要在 result 里明确标出来");
        assertEquals(1, ofType("log").size(), "同时落一条 log 说明，用户才知道回答被截了");
    }

    // ---------------- 失败与拒绝 ----------------

    @Test
    void failedTurnReportsErrorButKeepsSessionUsable() throws Exception {
        String baseUrl = serveOnce(500, "{\"error\":{\"message\":\"boom\"}}");
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));

        rt.startFirstTurn("在吗");

        SessionEvent result = awaitType("result", 5000);
        assertNotNull(result);
        assertEquals(true, result.payload().get("isError"));
        assertEquals("error", result.payload().get("subtype"));
        awaitState(rt, SessionState.WAITING_INPUT);
        assertTrue(rt.state() == SessionState.WAITING_INPUT,
                "端点这一轮没成不等于会话结束：留在 WAITING_INPUT 让用户改完再问，不判 FAILED");
        assertTrue(ofType("error").get(0).content().contains("500"));
        assertFalse(ofType("error").get(0).content().contains(KEY), "错误消息不得泄露密钥");
        assertTrue(exits.isEmpty(), "失败不触发会话收口回调");
    }

    @Test
    void injectingWhileGeneratingIsRejectedWithAnError() throws Exception {
        String baseUrl = serveSse(List.of(content("慢"), DONE), 1200);
        // flushChars=1：每片立刻成一条 text_delta，用例才能"趁生成中"做动作
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1, 100_000));

        rt.startFirstTurn("第一问");
        assertNotNull(awaitType("text_delta", 3000), "首片已到，说明正在生成");
        rt.injectInput("第二问");

        assertNotNull(awaitType("result", 6000));
        assertEquals(1, calls.get(), "生成中注入必须被拒，不能两条生成线程交错（会出现两个 result）");
        assertTrue(ofType("error").stream().anyMatch(e -> e.content().contains("还在生成中")),
                "拒绝要落 error 事件，否则用户以为消息发出去了");
        assertEquals(1, ofType("user").size());
    }

    @Test
    void imagesAreRejectedWithAnError() throws Exception {
        String baseUrl = serveSse(List.of(content("x"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));

        rt.injectInput("看看这张图", List.of(new InputImage("att1", "a.png", "image/png", "AAAA")));

        SessionEvent error = awaitType("error", 2000);
        assertNotNull(error);
        assertTrue(error.content().contains("不支持图片"), error.content());
        assertEquals(0, calls.get(), "不支持就该在本地拒掉，不发请求");
        assertEquals(0, ofType("user").size(), "被拒的输入不该进事件流（进了就等于承认它发出去了）");
    }

    // ---------------- 中断 / kill / finish ----------------

    @Test
    void interruptKeepsTheSessionAndThePartialAnswer() throws Exception {
        String baseUrl = serveSse(List.of(content("前半段"), DONE), 2000);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1, 100_000));

        rt.startFirstTurn("写长点");
        assertNotNull(awaitType("text_delta", 3000));

        assertTrue(rt.interruptTurn());

        SessionEvent result = awaitType("result", 5000);
        assertNotNull(result);
        assertEquals("interrupted", result.payload().get("subtype"));
        assertEquals(false, result.payload().get("isError"), "中断不是故障");
        assertEquals("前半段", ofType("assistant").get(0).content(), "已产出的部分要留下");
        awaitState(rt, SessionState.WAITING_INPUT);
        assertEquals(SessionState.WAITING_INPUT, rt.state(), "中断后会话要能继续提问");
        assertFalse(rt.generating());
        assertTrue(exits.isEmpty(), "中断不该结束会话");
    }

    @Test
    void interruptWithoutRunningTurnReportsFalse() {
        ModelSessionRuntime rt = runtime("http://127.0.0.1:1/v1", tuning(10_000, 1000, 100_000));

        assertFalse(rt.interruptTurn(), "没有在跑的回合 → 调用方按 409 处理");
    }

    @Test
    void killMidStreamLeavesNoResultBehind() throws Exception {
        String baseUrl = serveSse(List.of(content("半"), DONE), 2000);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1, 100_000));

        rt.startFirstTurn("写长点");
        assertNotNull(awaitType("text_delta", 3000));

        rt.kill();
        Thread.sleep(500);                            // 给回合线程跑完它的收尾路径

        assertEquals(SessionState.TERMINATED, rt.state());
        assertTrue(ofType("result").isEmpty(),
                "kill 后落 result 会被内核 dispatch 转回 WAITING_INPUT，把 TERMINATED 覆盖掉（实时态与落库劈叉）");
        assertTrue(ofType("assistant").isEmpty(), "被强杀的回合作废，不补全量 assistant");
        assertTrue(exits.isEmpty(), "kill 与 handleExit 不同：不走 onExit 回调（能力层自己写 TERMINATED）");
    }

    @Test
    void finishMidStreamKeepsPartialAnswerThenCloses() throws Exception {
        String baseUrl = serveSse(List.of(content("半句"), DONE), 2000);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1, 100_000));

        rt.startFirstTurn("写长点");
        assertNotNull(awaitType("text_delta", 3000));

        rt.finish();

        awaitState(rt, SessionState.DONE);
        assertEquals(SessionState.DONE, rt.state(), "finish 要收口到 DONE");
        assertEquals("半句", ofType("assistant").get(0).content(),
                "finish 时已产出的部分要落流（用户已经看到它了），直接收口就等于丢掉");
        assertEquals(List.of(true), exits);
    }

    @Test
    void finishWhileIdleClosesImmediately() throws Exception {
        String baseUrl = serveSse(List.of(content("答"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));
        rt.startFirstTurn("问");
        assertNotNull(awaitType("result", 5000));

        rt.finish();

        assertEquals(SessionState.DONE, rt.state());
        assertEquals(List.of(true), exits);
        assertFalse(rt.interruptTurn(), "收口后没有在跑的回合");
    }

    @Test
    void injectAfterCloseIsIgnored() throws Exception {
        String baseUrl = serveSse(List.of(content("答"), DONE), 0);
        ModelSessionRuntime rt = runtime(baseUrl, tuning(10_000, 1000, 100_000));
        rt.startFirstTurn("问");
        assertNotNull(awaitType("result", 5000));
        rt.kill();

        rt.injectInput("还能问吗");

        Thread.sleep(200);
        assertEquals(1, ofType("user").size(), "已收口的会话不再接受输入（能力层会先懒重挂再注入）");
        assertEquals(1, calls.get());
    }
}
