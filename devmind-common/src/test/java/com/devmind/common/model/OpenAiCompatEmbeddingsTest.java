package com.devmind.common.model;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-48 FR-05 共享向量调用的四条硬要求：分批（batch_size 真的生效）、单批失败重试一次、
 * 响应结构校验（条数/维度）、错误消息脱敏。真起 JDK HttpServer 收请求——不 mock HTTP 层，
 * 否则"到底发了几次请求"这类断言就失去意义。
 */
class OpenAiCompatEmbeddingsTest {

    private static final int DIMS = 4;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Integer> batchSizes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 每收到一次请求记一次批大小，按 emit 决定返回正常 JSON 还是错误 */
    private String serve(Responder responder) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/embeddings", ex -> {
            calls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = countInputs(body);
            batchSizes.add(n);
            Responder.Out out = responder.respond(n);
            byte[] bytes = out.body().getBytes(StandardCharsets.UTF_8);
            // 失败响应也带 body（sendResponseHeaders(status, -1) 会吞掉 body，脱敏就测不到了）
            ex.sendResponseHeaders(out.status(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    private interface Responder {
        record Out(int status, String body) {}

        Out respond(int inputCount);
    }

    /** 真解析请求体数 input 条数——"发了几次、每次几条"是本测试的核心断言，不能靠字符串数数 */
    private static int countInputs(String json) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(json).path("input").size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String vectorJson(int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"embedding\":[");
            for (int d = 0; d < DIMS; d++) {
                sb.append(d == 0 ? "0.5" : "0.1").append(d < DIMS - 1 ? "," : "");
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    private OpenAiCompatEmbeddings.Options options(String baseUrl, int batchSize) {
        return new OpenAiCompatEmbeddings.Options(baseUrl, "sk-secret-key-123", "text-embedding-3", 5, batchSize);
    }

    @Test
    void batchesByBatchSize() throws IOException {
        String baseUrl = serve(n -> new Responder.Out(200, vectorJson(n)));

        List<float[]> vectors = OpenAiCompatEmbeddings.embed(options(baseUrl, 1),
                List.of("a", "b", "c"));

        assertEquals(3, vectors.size());
        assertEquals(3, calls.get(), "batch_size=1 时 3 条文本必须发 3 次请求");
        assertEquals(List.of(1, 1, 1), batchSizes);
        assertEquals(DIMS, vectors.get(0).length);
    }

    @Test
    void singleBatchWhenBatchSizeCoversAll() throws IOException {
        String baseUrl = serve(n -> new Responder.Out(200, vectorJson(n)));

        OpenAiCompatEmbeddings.embed(options(baseUrl, 32), List.of("a", "b", "c"));

        assertEquals(1, calls.get());
        assertEquals(List.of(3), batchSizes, "batch_size 覆盖全部输入时应一次带完");
    }

    @Test
    void retriesOnceThenSucceeds() throws IOException {
        String baseUrl = serve(n -> calls.get() == 1
                ? new Responder.Out(500, "boom")
                : new Responder.Out(200, vectorJson(n)));

        List<float[]> vectors = OpenAiCompatEmbeddings.embed(options(baseUrl, 8), List.of("a"));

        assertEquals(1, vectors.size());
        assertEquals(2, calls.get(), "失败后应重试一次");
    }

    @Test
    void failureMessageCarriesBatchRangeAndNoApiKey() throws IOException {
        String baseUrl = serve(n -> new Responder.Out(500, "upstream said Bearer sk-secret-key-123 is invalid"));

        EmbeddingCallException ex = assertThrows(EmbeddingCallException.class,
                () -> OpenAiCompatEmbeddings.embed(options(baseUrl, 2), List.of("a", "b", "c")));

        assertTrue(ex.getMessage().contains("chunks[0,2)"), "失败消息要带批次区间: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("sk-secret-key-123"), "消息不得回显 apiKey: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("***"), "被回显的凭据片段应被抹掉");
        assertTrue(ex.getMessage().contains("/embeddings"), "失败消息要回显实际请求的地址: " + ex.getMessage());
    }

    @Test
    void rejectsResponseWithWrongVectorCount() throws IOException {
        String baseUrl = serve(n -> new Responder.Out(200, vectorJson(Math.max(0, n - 1))));

        EmbeddingCallException ex = assertThrows(EmbeddingCallException.class,
                () -> OpenAiCompatEmbeddings.embed(options(baseUrl, 8), List.of("a", "b")));
        assertTrue(ex.getMessage().contains("条数与输入不符"), ex.getMessage());
    }

    @Test
    void rejectsRaggedVectors() throws IOException {
        String baseUrl = serve(n -> new Responder.Out(200,
                "{\"data\":[{\"embedding\":[1,2]},{\"embedding\":[1,2,3]}]}"));

        EmbeddingCallException ex = assertThrows(EmbeddingCallException.class,
                () -> OpenAiCompatEmbeddings.embed(options(baseUrl, 8), List.of("a", "b")));
        assertTrue(ex.getMessage().contains("维度不一致"), ex.getMessage());
    }

    @Test
    void emptyInputDoesNotCallEndpoint() {
        assertEquals(List.of(), OpenAiCompatEmbeddings.embed(
                new OpenAiCompatEmbeddings.Options("http://127.0.0.1:1/v1", null, "m", 1, 8), List.of()));
    }

    @Test
    void sanitizeStripsCredentials() {
        assertEquals("*** / ***", OpenAiCompatEmbeddings.sanitize("sk-abcdef123456 / Bearer abc.def"));
        assertEquals("plain text", OpenAiCompatEmbeddings.sanitize("plain text"));
        assertEquals("", OpenAiCompatEmbeddings.sanitize(null));
    }

    /** FR-11 把 EmbeddingCallException 挂到了 ModelCallException 下：既有捕获点必须继续生效 */
    @Test
    void embeddingFailureIsAlsoAModelCallFailure() {
        assertThrows(EmbeddingCallException.class,
                () -> OpenAiCompatEmbeddings.embed(
                        new OpenAiCompatEmbeddings.Options("http://127.0.0.1:1/v1", null, "m", 1, 8),
                        List.of("a")));

        assertThrows(ModelCallException.class,
                () -> OpenAiCompatEmbeddings.embed(
                        new OpenAiCompatEmbeddings.Options("http://127.0.0.1:1/v1", null, "m", 1, 8),
                        List.of("a")));
    }
}
