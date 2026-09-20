package com.devmind.model;

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
}
