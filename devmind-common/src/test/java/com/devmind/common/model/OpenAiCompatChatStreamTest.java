package com.devmind.common.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-49 流式对话客户端：真起 JDK HttpServer 假 SSE（分段 flush）收请求。
 *
 * <p>钉死四类事：<b>取消/超时真的能收口</b>（中断 < 2s 返回且异常可辨识、两条看门狗）；<b>内容不被
 * 破坏</b>（换行保留、数组 content 拼接、多行 data 按规范拼回）；<b>网关偷懒的边界照旧能聊</b>
 * （忽略 stream 回普通 JSON、缺 {@code [DONE]}、注释行、CRLF）；<b>老坑不复发</b>（不带 Upgrade 头、
 * 失败消息脱敏且回显地址）。</p>
 */
class OpenAiCompatChatStreamTest {

    private static final String API_KEY = "sk-secret-key-123";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> upgrade = new AtomicReference<>();
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------- 假端点 ----------------

    private void record(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        path.set(ex.getRequestURI().getPath());
        auth.set(ex.getRequestHeaders().getFirst("Authorization"));
        upgrade.set(ex.getRequestHeaders().getFirst("Upgrade"));
        bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** 一次性响应（非流式）：用来测"网关忽略 stream:true"的回落与错误码 */
    private String serve(int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            try {
                record(ex);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 客户端取消
            } finally {
                ex.close();
            }
        });
        srv.start();
        server = srv;
        return baseUrl(srv);
    }

    /**
     * 分片流式响应：每片写完即 flush（chunked），首片之后可选停顿（触发停顿看门狗 / 给测试留出中断窗口）。
     *
     * @param headersDelayMs 发出响应头之前的延迟（触发首字节看门狗）
     */
    private String serveSse(List<String> chunks, long holdAfterFirstMs, long headersDelayMs) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            try {
                record(ex);
                if (headersDelayMs > 0) {
                    Thread.sleep(headersDelayMs);
                }
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);           // 0 = chunked，长度未知
                OutputStream out = ex.getResponseBody();
                for (int i = 0; i < chunks.size(); i++) {
                    out.write(chunks.get(i).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (i == 0 && holdAfterFirstMs > 0) {
                        Thread.sleep(holdAfterFirstMs);
                    }
                }
            } catch (Exception ignored) {
                // 客户端取消/看门狗中断都会让这里的写失败——单测不关心
            } finally {
                ex.close();
            }
        });
        srv.start();
        server = srv;
        return baseUrl(srv);
    }

    private static String baseUrl(HttpServer srv) {
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    /** 一个 SSE 分片：{@code deltaJson} 是 choices[0].delta 的 JSON */
    private static String chunk(String deltaJson) {
        return "data: {\"model\":\"m1\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson + "}]}\n\n";
    }

    private static String content(String text) {
        return chunk("{\"content\":\"" + text + "\"}");
    }

    private static final String DONE = "data: [DONE]\n\n";

    private static OpenAiCompatChatStream.Options quick(String baseUrl, int firstByte, int stall) {
        return new OpenAiCompatChatStream.Options(baseUrl, API_KEY, "m1", 5, firstByte, stall, 30);
    }

    private static List<OpenAiCompatChatStream.Message> oneTurn(String user) {
        return List.of(OpenAiCompatChatStream.Message.user(user));
    }

    /** 收集增量并返回完整正文 */
    private static final class Collector implements OpenAiCompatChatStream.DeltaListener {
        private final List<String> deltas = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onDelta(String text) {
            deltas.add(text);
            this.text.append(text);
        }
    }

    // ---------------- 正常流 ----------------

    @Test
    void streamsDeltasAndReturnsTextWithNewlinesIntact() throws IOException {
        String baseUrl = serveSse(List.of(
                content("第一行\\n"),
                content("第二行"),
                chunk("{}"),
                DONE), 0, 0);
        Collector got = new Collector();

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("你好"), got);

        assertEquals(List.of("第一行\n", "第二行"), got.deltas, "增量要逐段吐出（打字机效果的数据源）");
        assertEquals("第一行\n第二行", reply.text(), "正文必须保留换行——探针那套压空白截 200 字用在正文上就是损坏");
        assertFalse(reply.truncated());
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer " + API_KEY, auth.get());
        assertNull(upgrade.get(), "不得带 Upgrade 头（h2c 升级提议会让 uvicorn 丢请求体，2026-09 真机实锤）");
        String body = bodies.get(0);
        assertTrue(body.contains("\"stream\":true"), body);
        assertFalse(body.contains("max_tokens"), body);
        assertFalse(body.contains("stream_options"), body);
        assertEquals(1, calls.get());
    }

    @Test
    void sendsSystemAndHistoryInOrder() throws IOException {
        String baseUrl = serveSse(List.of(content("好"), DONE), 0, 0);

        OpenAiCompatChatStream.chatStream(quick(baseUrl, 5, 5), List.of(
                OpenAiCompatChatStream.Message.system("系统提示"),
                OpenAiCompatChatStream.Message.user("第一轮"),
                OpenAiCompatChatStream.Message.assistant("第一轮回复"),
                OpenAiCompatChatStream.Message.user("第二轮")), new Collector());

        String body = bodies.get(0);
        assertTrue(body.contains("\"role\":\"system\""), body);
        assertTrue(body.indexOf("系统提示") < body.indexOf("第一轮回复"), body);
        assertTrue(body.indexOf("第一轮回复") < body.indexOf("第二轮"), "messages 顺序即对话顺序: " + body);
    }

    @Test
    void multipleDataLinesOfOneEventAreJoinedWithNewline() throws IOException {
        // SSE 规范：同一事件的多个 data 行用 \n 拼回原文再解析（长 JSON 会被代理拆行）
        String baseUrl = serveSse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"甲\"}}\n"
                        + "data: ]}\n\n",
                DONE), 0, 0);
        Collector got = new Collector();

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), got);

        assertEquals("甲", reply.text());
    }

    @Test
    void joinsAllContentPartsOfAnArrayInsteadOfTheFirstOne() throws IOException {
        // OneAPI/vLLM 兼容层的数组 content：探针只取首个（够判断"有回复"），正文必须全拼
        String baseUrl = serveSse(List.of(
                chunk("{\"content\":[{\"type\":\"text\",\"text\":\"甲\"},{\"type\":\"text\",\"text\":\"乙\"}]}"),
                DONE), 0, 0);

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector());

        assertEquals("甲乙", reply.text());
    }

    @Test
    void skipsCommentLinesAndAcceptsCrlf() throws IOException {
        String baseUrl = serveSse(List.of(
                ": keep-alive\r\n\r\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"行\"}}]}\r\n\r\n",
                DONE), 0, 0);

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector());

        assertEquals("行", reply.text());
    }

    @Test
    void missingDoneIsNotAFailure() throws IOException {
        String baseUrl = serveSse(List.of(content("半句就断")), 0, 0);

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector());

        assertEquals("半句就断", reply.text(), "网关不给 [DONE] 只说明它偷懒，EOF 同样是正常结束");
    }

    @Test
    void reasoningContentStaysOutOfTheReply() throws IOException {
        String baseUrl = serveSse(List.of(
                chunk("{\"reasoning_content\":\"先想一下\"}"),
                content("答案"),
                DONE), 0, 0);

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector());

        assertEquals("答案", reply.text(), "思考内容不属于正文");
    }

    // ---------------- 网关偷懒的边界 ----------------

    @Test
    void fallsBackToNonStreamJsonWhenGatewayIgnoresStream() throws IOException {
        String baseUrl = serve(200, "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"整段回复\"},\"finish_reason\":\"stop\"}]}");
        Collector got = new Collector();

        OpenAiCompatChatStream.Reply reply = OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("你好"), got);

        assertEquals("整段回复", reply.text(), "忽略 stream:true 的网关必须照常能用（打字机退化成一跳）");
        assertEquals(List.of("整段回复"), got.deltas, "回落时也要给一次增量，否则前端拿不到任何流式信号");
    }

    @Test
    void zeroContentIsAFailure() throws IOException {
        String baseUrl = serveSse(List.of(DONE), 0, 0);

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector()));

        assertTrue(ex.getMessage().contains("无回复内容"), ex.getMessage());
    }

    @Test
    void reasoningOnlyReplyExplainsItself() throws IOException {
        String baseUrl = serveSse(List.of(chunk("{\"reasoning_content\":\"想了 20 秒\"}"), DONE), 0, 0);

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector()));

        assertTrue(ex.getMessage().contains("思考内容"),
                "只吐思考不吐正文时要说清成因，别让人以为被网关拦了: " + ex.getMessage());
    }

    @Test
    void errorObjectInsideA200StreamIsReported() throws IOException {
        String baseUrl = serveSse(List.of(
                content("起头"),
                "data: {\"error\":{\"message\":\"engine crashed, key Bearer " + API_KEY + "\"}}\n\n"), 0, 0);

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector()));

        assertTrue(ex.getMessage().contains("engine crashed"), ex.getMessage());
        assertFalse(ex.getMessage().contains(API_KEY), ex.getMessage());
        assertTrue(ex.getMessage().contains("***"), ex.getMessage());
    }

    @Test
    void brokenSseFrameIsReported() throws IOException {
        String baseUrl = serveSse(List.of("data: {不是 JSON\n\n"), 0, 0);

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector()));

        assertTrue(ex.getMessage().contains("不是合法 JSON"), ex.getMessage());
    }

    @Test
    void non2xxFailureIsSanitizedAndShowsRequestUrl() throws IOException {
        String baseUrl = serve(401, "{\"error\":{\"message\":\"invalid key: Bearer " + API_KEY + "\"}}");

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 5, 5), oneTurn("hi"), new Collector()));

        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        assertFalse(ex.getMessage().contains(API_KEY), ex.getMessage());
        assertTrue(ex.getMessage().contains("/chat/completions"), ex.getMessage());
    }

    // ---------------- 取消与看门狗 ----------------

    @Test
    void interruptStopsTheReadLoopQuicklyAndIsDistinguishable() throws IOException, InterruptedException {
        String baseUrl = serveSse(List.of(content("已经吐出的前半"), DONE), 3000, 0);
        Collector got = new Collector();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch firstDelta = new CountDownLatch(1);
        Thread turn = new Thread(() -> {
            try {
                OpenAiCompatChatStream.chatStream(quick(baseUrl, 5, 30), oneTurn("hi"),
                        text -> {
                            got.onDelta(text);
                            firstDelta.countDown();
                        });
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "cap49-stream-turn");
        turn.start();
        assertTrue(firstDelta.await(5, TimeUnit.SECONDS), "首片应已到达");

        long startedAt = System.nanoTime();
        turn.interrupt();                                  // = 用户点「停止生成」
        turn.join(2000);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(turn.isAlive(), "中断后读循环必须立刻收口（不然线程会一直挂着）");
        assertTrue(elapsedMs < 2000, "中断到返回耗时 " + elapsedMs + "ms");
        assertTrue(thrown.get() instanceof ModelInterruptedException,
                "中断要与网络失败区分开，调用方才知道该保留部分正文而不是报故障: " + thrown.get());
        assertEquals(List.of("已经吐出的前半"), got.deltas, "已产出的部分要留着");
    }

    @Test
    void stallWatchdogAbortsASilentlyStuckStream() throws IOException {
        // 首片之后端点不再吐字（模型卡死/连接半死）：必须有人收口，否则会话永远停在 RUNNING
        String baseUrl = serveSse(List.of(content("起"), DONE), 3000, 0);
        long startedAt = System.nanoTime();

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 30, 1), oneTurn("hi"), new Collector()));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(ex.getMessage().contains("没有新内容"), ex.getMessage());
        assertTrue(elapsedMs < 3000, "停顿看门狗应在 3s 内收口，实际 " + elapsedMs + "ms");
    }

    @Test
    void firstByteWatchdogAbortsAnUnresponsiveEndpoint() throws IOException {
        String baseUrl = serveSse(List.of(content("太迟了"), DONE), 0, 3000);
        long startedAt = System.nanoTime();

        ModelCallException ex = assertThrows(ModelCallException.class, () -> OpenAiCompatChatStream.chatStream(
                quick(baseUrl, 1, 30), oneTurn("hi"), new Collector()));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(ex.getMessage().contains("未返回响应"), ex.getMessage());
        assertTrue(elapsedMs < 3000, "首字节看门狗应在 3s 内收口，实际 " + elapsedMs + "ms");
    }

    @Test
    void withoutApiKeyNoAuthorizationHeaderIsSent() throws IOException {
        String baseUrl = serveSse(List.of(content("ok"), DONE), 0, 0);

        OpenAiCompatChatStream.chatStream(new OpenAiCompatChatStream.Options(
                baseUrl, null, "local-model", 5, 5, 5, 30), oneTurn("hi"), new Collector());

        assertNull(auth.get(), "无凭据（本地服务）时不发 Authorization 头");
    }

    @Test
    void optionsOfDerivesSaneWatchdogs() {
        OpenAiCompatChatStream.Options opt = OpenAiCompatChatStream.Options.of("http://h/v1", "k", "m", 30);

        assertEquals(30, opt.connectTimeoutSeconds());
        assertEquals(60, opt.firstByteTimeoutSeconds(), "思考型模型首字节可能很久，别拿端点超时硬卡");
        assertEquals(120, opt.stallTimeoutSeconds());
        assertEquals(600, opt.turnMaxSeconds());

        OpenAiCompatChatStream.Options small = OpenAiCompatChatStream.Options.of("http://h/v1", "k", "m", 0);
        assertEquals(30, small.connectTimeoutSeconds(), "0/未配超时要有兜底值，否则 connectTimeout 直接抛");
    }
}
