package com.devmind.model;

import com.devmind.common.exception.DevMindException;
import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.config.ModelProperties;
import com.devmind.model.dto.EndpointTestResult;
import com.devmind.model.dto.ModelEndpointRequest;
import com.devmind.model.repo.ModelEndpointRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-48 FR-03 连接测试：真起 JDK HttpServer 收请求，验证"实测维度回写"这条唯一允许的维度来源，
 * 以及 FR-06 的维度变化告警、失败时不覆盖旧维度、草稿预检不落库。
 */
class ModelEndpointProbeTest {

    private static final String API_KEY = "sk-secret-key-123";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final List<Integer> batchSizes = new ArrayList<>();
    private final List<String> chatBodies = new ArrayList<>();

    private ModelEndpointRepository repo;
    private ModelCipher cipher;
    private ModelEndpointService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ModelProperties props = new ModelProperties();
        props.setCryptoKey("test-master-key");
        cipher = new ModelCipher(props);
        cipher.init();

        repo = mock(ModelEndpointRepository.class);
        ObjectProvider<com.devmind.common.model.ModelEndpointUsageProvider> usages = mock(ObjectProvider.class);
        lenient().when(usages.orderedStream()).thenReturn(Stream.empty());
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        EmbeddingSeedProperties seed = new EmbeddingSeedProperties();
        seed.setDimensions(64);
        service = new ModelEndpointService(repo, cipher, seed, new TransactionTemplate(tm), usages);
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个 /v1/embeddings，按 dims 返回定长向量；status=500 时返回错误体（含被回显的密钥） */
    private String serve(int dims, int status) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/embeddings", ex -> {
            calls.incrementAndGet();
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            batchSizes.add(countInputs(body));
            String json = status == 200 ? vectorJson(dims, countInputs(body))
                    : "{\"error\":\"rejected Bearer " + API_KEY + "\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    /**
     * 起一个 {@code /v1/chat/completions}，返回 {@code choices[0].message.content = contentJson}
     * （JSON 片段由调用方给，便于造空回复/数组形态等）；status≠200 时回显密钥，验证脱敏。
     */
    private String serveChat(String contentJson, int status) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            chatBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = status == 200
                    ? "{\"model\":\"gpt-4o-mini\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                            + "\"content\":" + contentJson + "}}]}"
                    : "{\"error\":{\"message\":\"invalid key: Bearer " + API_KEY + "\"}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
    }

    private static int countInputs(String json) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(json).path("input").size();
        } catch (Exception e) {
            return 1;
        }
    }

    private static String vectorJson(int dims, int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"embedding\":[");
            for (int d = 0; d < dims; d++) {
                sb.append("0.1").append(d < dims - 1 ? "," : "");
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /** 已存的 openai-compatible 端点（带加密凭据）并挂到 repo.findById 上 */
    private ModelEndpointEntity stored(long id, String baseUrl, Integer dimensions) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setId(id);
        e.setKind(ModelEndpointEntity.KIND_EMBEDDING);
        e.setName("端点" + id);
        e.setProvider(ModelEndpointEntity.PROVIDER_OPENAI);
        e.setBaseUrl(baseUrl);
        e.setModel("bge-m3");
        e.setApiKeyEnc(cipher.encrypt(API_KEY));
        e.setDimensions(dimensions);
        e.setStatus(ModelEndpointEntity.STATUS_ACTIVE);
        e.setTimeoutSeconds(5);
        e.setBatchSize(32);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    /** 一个 mock provider 端点（无 baseUrl、无凭据） */
    private ModelEndpointEntity storedMock(long id, Integer dimensions) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setId(id);
        e.setKind(ModelEndpointEntity.KIND_EMBEDDING);
        e.setName("内置 Mock");
        e.setProvider(ModelEndpointEntity.PROVIDER_MOCK);
        e.setModel(com.devmind.common.model.ModelEndpointView.MODEL_MOCK);
        e.setDimensions(dimensions);
        e.setStatus(ModelEndpointEntity.STATUS_ACTIVE);
        e.setTimeoutSeconds(5);
        e.setBatchSize(32);
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    /** 已存的对话（openai-compatible）端点：有传输参数、无维度 */
    private ModelEndpointEntity storedChat(long id, String baseUrl) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setId(id);
        e.setKind(ModelEndpointEntity.KIND_CHAT);
        e.setName("通用模型" + id);
        e.setProvider(ModelEndpointEntity.PROVIDER_OPENAI);
        e.setBaseUrl(baseUrl);
        e.setModel("gpt-4o-mini");
        e.setApiKeyEnc(cipher.encrypt(API_KEY));
        e.setStatus(ModelEndpointEntity.STATUS_ACTIVE);
        e.setTimeoutSeconds(5);
        e.setBatchSize(32);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    private ModelEndpointEntity storedChatMock(long id) {
        ModelEndpointEntity e = storedChat(id, null);
        e.setProvider(ModelEndpointEntity.PROVIDER_MOCK);
        return e;
    }

    // ---------------- 成功路径 ----------------

    @Test
    void writesBackProbedDimensionsAndUsesDecryptedKeyOnTheWire() throws IOException {
        String baseUrl = serve(1024, 200);
        ModelEndpointEntity e = stored(1L, baseUrl, null);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertEquals(1024, r.dimensions());
        assertEquals(1, calls.get());
        assertEquals(List.of(1), batchSizes, "探针只发 1 条文本");
        assertEquals("Bearer " + API_KEY, authHeader.get(), "解密后的密钥必须真的进请求头");
        assertEquals(1024, e.getDimensions(), "实测维度必须回写端点");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
        assertNotNull(e.getLastTestAt());
        assertNull(r.dimensionChanged(), "首次探测（原本无维度）不算变化告警");
    }

    @Test
    void reportsDimensionChangeWhenModelSwitches() throws IOException {
        String baseUrl = serve(1024, 200);
        ModelEndpointEntity e = stored(1L, baseUrl, 64);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertEquals(1024, r.dimensions());
        assertEquals(new EndpointTestResult.DimensionChange(64, 1024), r.dimensionChanged());
        assertEquals(1024, e.getDimensions(), "维度变化后端点记录必须跟着实测走");
        assertTrue(r.message().contains("原记录维度 64"), r.message());
    }

    @Test
    void reTestAtSameDimensionReportsNoChange() throws IOException {
        String baseUrl = serve(64, 200);
        stored(1L, baseUrl, 64);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertNull(r.dimensionChanged(), "维度没变就不该弹告警");
    }

    @Test
    void mockEndpointSelfReportsWithoutNetwork() {
        ModelEndpointEntity e = storedMock(99L, 64);

        EndpointTestResult r = service.test(99L);

        assertTrue(r.ok(), r.message());
        assertEquals(64, r.dimensions());
        assertEquals(0, calls.get(), "mock 分支不许发网络请求");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
    }

    // ---------------- 失败路径 ----------------

    @Test
    void failureKeepsPreviousDimensionsAndSanitizesMessage() throws IOException {
        String baseUrl = serve(1024, 500);
        ModelEndpointEntity e = stored(1L, baseUrl, 64);

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertNull(r.dimensions());
        assertEquals(64, e.getDimensions(), "测试失败不能把已记录的维度抹掉");
        assertEquals(Boolean.FALSE, e.getLastTestOk());
        assertNotNull(e.getLastTestMessage());
        assertFalse(e.getLastTestMessage().contains(API_KEY), "回写消息不得含明文密钥: " + e.getLastTestMessage());
        assertTrue(e.getLastTestMessage().contains("***"), e.getLastTestMessage());
    }

    @Test
    void brokenCredentialIsReportedInsteadOfThrowing() {
        ModelEndpointEntity e = stored(1L, "https://api.example.com/v1", 64);
        e.setApiKeyEnc("enc1:not-a-valid-ciphertext");

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertTrue(r.message().contains("凭据解密失败"), r.message());
        assertEquals(Boolean.FALSE, e.getLastTestOk());
        assertEquals(64, e.getDimensions(), "解密失败不触碰维度");
        assertEquals(0, calls.get());
    }

    @Test
    void openAiEndpointWithoutBaseUrlOrModelFailsWithActionableMessage() {
        ModelEndpointEntity e = storedMock(99L, null);
        e.setProvider(ModelEndpointEntity.PROVIDER_OPENAI); // mock 名换 openai 但没填传输参数

        EndpointTestResult r = service.test(99L);

        assertFalse(r.ok());
        assertTrue(r.message().contains("baseUrl"), r.message());
        assertEquals(0, calls.get());
    }

    // ---------------- 草稿预检 ----------------

    @Test
    void draftTestDoesNotPersistAnything() throws IOException {
        String baseUrl = serve(768, 200);
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "草稿", "openai-compatible",
                baseUrl, API_KEY, "bge-m3", 5, null, null, null, null);

        EndpointTestResult r = service.testDraft(req);

        assertTrue(r.ok(), r.message());
        assertEquals(768, r.dimensions());
        verify(repo, never()).save(any());
        assertEquals(1, calls.get());
    }

    @Test
    void draftTestReportsFailureWithoutTouchingRepo() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "草稿", "openai-compatible",
                "http://127.0.0.1:1/v1", null, "bge-m3", 2, null, null, null, null);

        EndpointTestResult r = service.testDraft(req);

        assertFalse(r.ok());
        verify(repo, never()).save(any());
        verify(repo, never()).findById(anyLong());
    }

    @Test
    void draftMockDraftReportsSeedDimensions() {
        EndpointTestResult r = service.testDraft(new ModelEndpointRequest("EMBEDDING", "草稿", "mock",
                null, null, null, null, null, null, null, null));

        assertTrue(r.ok(), r.message());
        assertEquals(64, r.dimensions());
    }

    // ---------------- FR-11 通用模型（CHAT）探针 ----------------

    @Test
    void chatProbeHitsChatCompletionsAndNeverWritesDimensions() throws IOException {
        String baseUrl = serveChat("\"可用\"", 200);
        ModelEndpointEntity e = storedChat(1L, baseUrl);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertEquals(1, calls.get(), "对话探针不得打到 /embeddings");
        assertNull(r.dimensions(), "对话端点没有维度这回事");
        assertNull(r.dimensionChanged());
        assertTrue(r.message().contains("可用"), "message 要带上模型真实回复: " + r.message());
        assertEquals("Bearer " + API_KEY, authHeader.get(), "解密后的密钥必须真的进请求头");
        assertNull(e.getDimensions(), "对话端点不得被写入维度");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
        String body = chatBodies.get(0);
        assertTrue(body.contains("\"model\""), body);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertFalse(body.contains("max_tokens"), "部分网关对 max_tokens 直接 400: " + body);
    }

    @Test
    void chatProbeTruncatesLongReplyIntoMessage() throws IOException {
        String baseUrl = serveChat("\"" + "很长的回复".repeat(40) + "\"", 200);
        storedChat(1L, baseUrl);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertTrue(r.message().endsWith("…"), "长回复要在 service 侧截断再进 message: " + r.message().length());
        assertTrue(r.message().length() < 200, "message 会落库进 UI，不能塞千字回复");
    }

    @Test
    void chatProbeFailureIsSanitizedAndTouchesNothing() throws IOException {
        String baseUrl = serveChat("\"x\"", 401);
        ModelEndpointEntity e = storedChat(1L, baseUrl);

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertFalse(e.getLastTestMessage().contains(API_KEY), e.getLastTestMessage());
        assertTrue(e.getLastTestMessage().contains("***"), e.getLastTestMessage());
        assertEquals(Boolean.FALSE, e.getLastTestOk());
        assertNull(e.getDimensions());
    }

    @Test
    void chatProbeWithBlankReplyIsAFailureAndDoesNotRetry() throws IOException {
        String baseUrl = serveChat("\"   \"", 200);
        storedChat(1L, baseUrl);

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok(), "空回复几乎总是网关拦截/限流，不能算连通");
        assertEquals(1, calls.get(), "探针由人盯着，不许重试（双倍耗时双倍计费）");
        assertTrue(r.message().contains("空"), r.message());
    }

    @Test
    void mockChatEndpointSelfRepliesWithoutNetwork() {
        ModelEndpointEntity e = storedChatMock(2L);

        EndpointTestResult r = service.test(2L);

        assertTrue(r.ok(), r.message());
        assertEquals(0, calls.get(), "mock 分支不许发网络请求");
        assertNull(r.dimensions());
        assertEquals("gpt-4o-mini", r.model(), "对话 mock 不得冒用 mock-embedding（索引血缘的合同值）");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
    }

    @Test
    void chatDraftDispatchesByKindWithoutPersisting() throws IOException {
        String baseUrl = serveChat("\"收到\"", 200);
        ModelEndpointRequest req = new ModelEndpointRequest("CHAT", "草稿对话", "openai-compatible",
                baseUrl, API_KEY, "gpt-4o-mini", 5, null, null, null, null);

        EndpointTestResult r = service.testDraft(req);

        assertTrue(r.ok(), r.message());
        assertEquals(1, calls.get());
        assertNull(r.dimensions(), "草稿预检同样不该给出维度");
        verify(repo, never()).save(any());
    }

    @Test
    void draftTestRejectsReservedKindBeforeAnyNetworkCall() {
        ModelEndpointRequest req = new ModelEndpointRequest("RERANK", "草稿重排", "openai-compatible",
                "https://api.example.com/v1", null, "bge-reranker", 5, null, null, null, null);

        assertThrows(DevMindException.class, () -> service.testDraft(req));

        assertEquals(0, calls.get(), "预留类型必须在发出请求前就拦住");
        verify(repo, never()).findById(anyLong());
    }
}
