package com.devmind.common.model;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-48 FR-11 对话探针客户端：真起 JDK HttpServer 收请求，钉死"打到哪、带了什么、什么算连通"——
 * 路径写错（打成 /embeddings）或判定过松（2xx 即算通）都是必须在单测里拦住的事。
 */
class OpenAiCompatChatTest {

    private static final String API_KEY = "sk-secret-key-123";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个 /v1/chat/completions，按给定状态码与响应体回话；请求路径/凭据/请求体全部留档 */
    private String serve(int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    private static String choicesJson(String contentJson) {
        return "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + contentJson + "},\"finish_reason\":\"stop\"}]}";
    }

    private OpenAiCompatChat.Options options(String baseUrl) {
        return new OpenAiCompatChat.Options(baseUrl, API_KEY, "gpt-4o-mini", 5);
    }

    @Test
    void postsUserMessageToChatCompletionsWithModel() throws IOException {
        String baseUrl = serve(200, choicesJson("\"可用\""));

        String reply = OpenAiCompatChat.chat(options(baseUrl), "__devmind_chat_probe__");

        assertEquals("可用", reply);
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer " + API_KEY, auth.get(), "解密后的密钥必须真的进请求头");
        assertEquals(1, calls.get());
        String body = bodies.get(0);
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""), body);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertTrue(body.contains("__devmind_chat_probe__"), body);
        assertFalse(body.contains("max_tokens"), "不传 max_tokens：部分网关/推理模型会直接 400: " + body);
    }

    @Test
    void acceptsContentAsArrayOfPartsAndCollapsesWhitespace() throws IOException {
        // OneAPI / vLLM 兼容层会把 content 回成 parts 数组，且回复里带换行
        String baseUrl = serve(200, choicesJson(
                "[{\"type\":\"text\",\"text\":\"第一行\\n\\n第二行\"}]"));

        String reply = OpenAiCompatChat.chat(options(baseUrl), "hi");

        assertEquals("第一行 第二行", reply, "多行回复要压成一行——它会被塞进 UI 与落库消息");
    }

    @Test
    void failureMessageIsSanitized() throws IOException {
        String baseUrl = serve(401, "{\"error\":{\"message\":\"invalid key: Bearer " + API_KEY + "\"}}");

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> OpenAiCompatChat.chat(options(baseUrl), "hi"));

        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
        assertFalse(ex.getMessage().contains(API_KEY), ex.getMessage());
        assertTrue(ex.getMessage().contains("***"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/chat/completions"),
                "失败消息要回显实际请求的地址，否则「baseUrl 填短了」只能靠猜: " + ex.getMessage());
    }

    @Test
    void notFoundMessagePointsAtBaseUrlAndModelName() throws IOException {
        // vLLM/SGLang 的 baseUrl 少了 /v1 时就是这一句（FastAPI 默认 404），
        // 而模型名不存在 vLLM 也回 404 —— 两种成因都得提，否则用户会去查网络
        String baseUrl = serve(404, "{\"detail\":\"Not Found\"}");

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> OpenAiCompatChat.chat(options(baseUrl), "hi"));

        assertTrue(ex.getMessage().contains("404"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/v1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("模型名"), ex.getMessage());
    }

    @Test
    void emptyChoicesIsAFailure() throws IOException {
        String baseUrl = serve(200, "{\"choices\":[]}");

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> OpenAiCompatChat.chat(options(baseUrl), "hi"));

        assertTrue(ex.getMessage().contains("choices"), ex.getMessage());
    }

    @Test
    void blankReplyIsAFailureEvenOn2xx() throws IOException {
        String baseUrl = serve(200, choicesJson("\"   \""));

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> OpenAiCompatChat.chat(options(baseUrl), "hi"));

        assertTrue(ex.getMessage().contains("content"), ex.getMessage());
    }

    @Test
    void nonJsonSuccessBodyIsReportedAsFailureNotRawJacksonError() throws IOException {
        String baseUrl = serve(200, "<html>gateway blocked</html>");

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> OpenAiCompatChat.chat(options(baseUrl), "hi"));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("JSON"), ex.getMessage());
    }

    @Test
    void withoutApiKeyNoAuthorizationHeaderIsSent() throws IOException {
        String baseUrl = serve(200, choicesJson("\"ok\""));

        OpenAiCompatChat.chat(new OpenAiCompatChat.Options(baseUrl, null, "local-model", 5), "hi");

        assertEquals(null, auth.get(), "无凭据（本地服务）时不发 Authorization 头");
    }
}
