package com.devmind.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.common.agent.tool.ToolCall;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CAP-76 FR-06：OpenAI 兼容 tools 调用协议（请求拼装 / 响应解析 / 降级判定）。 */
class OpenAiCompatToolChatTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatToolChat.Options options() {
        return new OpenAiCompatToolChat.Options(
                "http://127.0.0.1:" + server.getAddress().getPort(), "sk-secret-key", "test-model", 5, 0.1);
    }

    @Test
    void sendsToolsAndParsesToolCalls() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"",
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"get_requirement","arguments":"{\\"requirementId\\":\\"REQ-1\\"}"}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                """;

        var completion = OpenAiCompatToolChat.complete(options(),
                List.of(OpenAiCompatToolChat.Message.system("sys"),
                        OpenAiCompatToolChat.Message.user("REQ-1 什么状态")),
                List.of(new OpenAiCompatToolChat.ToolDef("get_requirement", "查需求",
                        "{\"type\":\"object\",\"properties\":{\"requirementId\":{\"type\":\"string\"}}}")));

        assertTrue(completion.hasToolCalls());
        assertEquals("tool_calls", completion.finishReason());
        assertEquals("get_requirement", completion.toolCalls().get(0).name());
        assertEquals("REQ-1", completion.toolCalls().get(0).args().get("requirementId"));
        assertEquals(14, completion.totalTokens());

        String sent = requestBody.get();
        assertTrue(sent.contains("\"tools\""));
        assertTrue(sent.contains("\"tool_choice\":\"auto\""));
        assertTrue(sent.contains("\"model\":\"test-model\""));
    }

    @Test
    void surfacesUnsupportedToolsAsStatusException() {
        status = 400;
        responseBody = "{\"error\":{\"message\":\"tools are not supported by this model\"}}";

        var e = assertThrows(OpenAiCompatToolChat.HttpStatusException.class,
                () -> OpenAiCompatToolChat.complete(options(),
                        List.of(OpenAiCompatToolChat.Message.user("hi")),
                        List.of(new OpenAiCompatToolChat.ToolDef("t", "d", "{}"))));

        assertEquals(400, e.statusCode());
        assertTrue(e.toolUnsupported());
        assertFalse(e.getMessage().contains("sk-secret-key"));
    }

    @Test
    void invalidArgumentsBecomeEmptyMap() {
        responseBody = """
                {"choices":[{"message":{"content":"hi",
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"t","arguments":"NOT JSON"}}]}}]}
                """;
        var completion = OpenAiCompatToolChat.complete(options(),
                List.of(OpenAiCompatToolChat.Message.user("hi")), List.of());
        assertEquals(Map.of(), completion.toolCalls().get(0).args());
    }

    @Test
    void acceptsObjectArguments() {
        responseBody = """
                {"choices":[{"message":{"content":"",
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"t","arguments":{"projectId":"p1"}}}]}}]}
                """;
        var completion = OpenAiCompatToolChat.complete(options(),
                List.of(OpenAiCompatToolChat.Message.user("hi")), List.of());
        assertEquals("p1", completion.toolCalls().get(0).args().get("projectId"));
    }

    @Test
    void rejectsEmptyResponse() {
        responseBody = "{\"choices\":[]}";
        assertThrows(ModelCallException.class,
                () -> OpenAiCompatToolChat.complete(options(),
                        List.of(OpenAiCompatToolChat.Message.user("hi")), List.of()));
    }

    @Test
    void roundTripsAssistantToolCallMessages() {
        responseBody = """
                {"choices":[{"message":{"content":"done"}}]}
                """;
        OpenAiCompatToolChat.complete(options(), List.of(
                OpenAiCompatToolChat.Message.assistant("",
                        List.of(new ToolCall("call_1", "t", Map.of("a", 1)))),
                OpenAiCompatToolChat.Message.tool("call_1", "ok")), List.of());

        String sent = requestBody.get();
        assertTrue(sent.contains("\"tool_calls\""));
        assertTrue(sent.contains("\"tool_call_id\":\"call_1\""));
    }
}
