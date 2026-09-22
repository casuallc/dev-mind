package com.devmind.model;

import com.devmind.common.exception.DevMindException;
import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.config.ModelProperties;
import com.devmind.model.dto.EndpointTestResult;
import com.devmind.model.dto.ModelEndpointRequest;
import com.devmind.model.repo.ModelEndpointRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * CAP-55 FR-02 决策端点连接测试：真起 JDK HttpServer 当假边车，验证"两段实调"
 * （{@code /healthz} → 固定样例 {@code /v1/predict}）、健康检查不过就不白跑一次前向、
 * 以及 model（checkpoint 别名）可空、kind/provider 配对校验。
 */
class ModelEndpointDecisionProbeTest {

    private static final String API_KEY = "sk-decision-key-7";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();

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

    // ---------------- 假边车 ----------------

    /** 假边车：{@code /healthz} 与 {@code /v1/predict} 可按用例给响应体与状态码 */
    private String serve(String healthzJson, String predictJson, int predictStatus) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean predict = "/v1/predict".equals(ex.getRequestURI().getPath());
            String json = predict ? predictJson : healthzJson;
            int status = predict ? predictStatus : 200;
            if (json == null) {
                json = "{\"detail\":\"Not Found\"}";
                status = 404;
            }
            respond(ex, status, json);
        });
        srv.start();
        server = srv;
        return "http://127.0.0.1:" + srv.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String healthzOk() {
        return "{\"status\":\"ok\",\"laya_version\":\"0.3.5\",\"loaded\":[\"multilingual\"],"
                + "\"devices\":{\"multilingual\":\"cpu\"},\"cuda_available\":false}";
    }

    private static String predictOk() {
        return "{\"model\":\"laya-rl-agent\",\"answers\":{\"sample_choice\":{\"type\":\"choice\","
                + "\"choice\":\"keep\",\"probabilities\":{\"keep\":0.93,\"discard\":0.07},"
                + "\"confidence\":0.93}},\"routing\":{\"model\":\"multilingual\","
                + "\"reason\":\"non-Latin script (han, 65% of letters)\"}}";
    }

    /** 已存的决策端点；model 传 null 表示"由边车自己路由" */
    private ModelEndpointEntity storedDecision(long id, String baseUrl, String model) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setId(id);
        e.setKind(ModelEndpointEntity.KIND_DECISION);
        e.setName("laya 决策边车" + id);
        e.setProvider(ModelEndpointEntity.PROVIDER_LAYA);
        e.setBaseUrl(baseUrl);
        e.setModel(model);
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

    private static ModelEndpointRequest decisionDraft(String baseUrl, String model, String provider) {
        return new ModelEndpointRequest("DECISION", "草稿决策端点", provider, baseUrl, null, model,
                5, null, null, null, null);
    }

    // ---------------- 成功路径 ----------------

    @Test
    void decisionProbeHitsHealthzThenSamplePredictAndNeverWritesDimensions() throws IOException {
        String baseUrl = serve(healthzOk(), predictOk(), 200);
        ModelEndpointEntity e = storedDecision(1L, baseUrl, null);

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertEquals(List.of("/healthz", "/v1/predict"), paths, "两段实调，顺序固定：先存活再样例");
        assertEquals("Bearer " + API_KEY, authHeader.get(), "解密后的密钥必须真的进请求头");
        assertNull(r.dimensions(), "决策端点没有维度这回事");
        assertNull(r.dimensionChanged());
        assertNull(e.getDimensions(), "决策端点不得被写入维度");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
        assertTrue(r.message().contains("laya 0.3.5"), r.message());
        assertTrue(r.message().contains("常驻 multilingual"), r.message());
        assertTrue(r.message().contains("sample_choice=keep（93%）"), "样例答案要连概率一起报出来: " + r.message());
        assertTrue(r.message().contains("non-Latin script"), "routing.reason 要透出来（多半是语言不匹配）");
        assertFalse(bodies.get(1).contains("\"model\""), "model 未配时不该往请求体里塞该键: " + bodies.get(1));
    }

    @Test
    void decisionProbePassesConfiguredCheckpointAsModel() throws IOException {
        String baseUrl = serve(healthzOk(), predictOk(), 200);
        storedDecision(1L, baseUrl, "multilingual");

        EndpointTestResult r = service.test(1L);

        assertTrue(r.ok(), r.message());
        assertTrue(bodies.get(1).contains("\"model\":\"multilingual\""), bodies.get(1));
        assertEquals("multilingual", r.model());
    }

    @Test
    void decisionProbeSendsChineseStateAsUtf8() throws IOException {
        String baseUrl = serve(healthzOk(), predictOk(), 200);
        storedDecision(1L, baseUrl, null);

        assertTrue(service.test(1L).ok());

        assertTrue(bodies.get(1).contains("构建失败时先看 StepRunner 日志 Hub"), bodies.get(1));
        assertTrue(bodies.get(1).contains("\"questions\""), bodies.get(1));
    }

    // ---------------- 失败路径 ----------------

    @Test
    void decisionProbeSkipsSampleCallWhenHealthzIsNotOk() throws IOException {
        String baseUrl = serve("{\"status\":\"loading\",\"laya_version\":\"0.3.5\",\"loaded\":[]}",
                predictOk(), 200);
        ModelEndpointEntity e = storedDecision(1L, baseUrl, null);

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertEquals(List.of("/healthz"), paths, "边车没就绪就不再发样例题：白跑一次前向没意义");
        assertTrue(r.message().contains("未就绪"), r.message());
        assertEquals(Boolean.FALSE, e.getLastTestOk());
    }

    @Test
    void decisionProbeReportsSampleFailureWithSidecarDetail() throws IOException {
        String baseUrl = serve(healthzOk(),
                "{\"detail\":\"Unknown model 'multi-lingual'. Available: english, multilingual\"}", 400);
        ModelEndpointEntity e = storedDecision(1L, baseUrl, "multi-lingual");

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertTrue(r.message().contains("400"), r.message());
        assertTrue(r.message().contains("Available: english, multilingual"),
                "边车给的可用别名列表要原样透出来，否则用户只能猜: " + r.message());
        assertEquals(Boolean.FALSE, e.getLastTestOk());
        assertNull(e.getDimensions());
    }

    @Test
    void decisionProbeOnUnreachableSidecarFailsWithoutThrowing() {
        ModelEndpointEntity e = storedDecision(1L, "http://127.0.0.1:1", null);

        EndpointTestResult r = service.test(1L);

        assertFalse(r.ok());
        assertTrue(r.message().contains("http://127.0.0.1:1/healthz"),
                "连不上时消息必须带上实际打的地址，否则运维只能猜: " + r.message());
        assertEquals(Boolean.FALSE, e.getLastTestOk());
    }

    @Test
    void decisionProbeRequiresBaseUrlButNotModel() {
        ModelEndpointRequest req = decisionDraft(null, null, ModelEndpointEntity.PROVIDER_LAYA);

        EndpointTestResult r = service.testDraft(req);

        assertFalse(r.ok());
        assertTrue(r.message().contains("baseUrl"), r.message());
        assertTrue(r.message().contains("模型名可空"), r.message());
        assertTrue(paths.isEmpty(), "必填项没齐就不该发请求");
    }

    // ---------------- 草稿预检与 mock ----------------

    @Test
    void decisionDraftWithoutModelIsAcceptedAndNotPersisted() throws IOException {
        String baseUrl = serve(healthzOk(), predictOk(), 200);

        EndpointTestResult r = service.testDraft(decisionDraft(baseUrl, null, ModelEndpointEntity.PROVIDER_LAYA));

        assertTrue(r.ok(), r.message());
        assertNull(r.model(), "未指定 checkpoint 时如实报空，不要编一个名字");
        verify(repo, never()).save(any());
        verify(repo, never()).findById(anyLong());
    }

    @Test
    void decisionDraftDefaultsToLayaProviderWhenOmitted() throws IOException {
        String baseUrl = serve(healthzOk(), predictOk(), 200);

        EndpointTestResult r = service.testDraft(decisionDraft(baseUrl, null, null));

        assertTrue(r.ok(), r.message());
        assertEquals(List.of("/healthz", "/v1/predict"), paths, "provider 省略时按 kind 落 laya，走同一个探针");
    }

    @Test
    void mockDecisionEndpointSelfReportsWithoutNetwork() {
        ModelEndpointEntity e = storedDecision(2L, null, "multilingual");
        e.setProvider(ModelEndpointEntity.PROVIDER_MOCK);

        EndpointTestResult r = service.test(2L);

        assertTrue(r.ok(), r.message());
        assertTrue(paths.isEmpty(), "mock 分支不许发网络请求");
        assertNull(r.dimensions());
        assertEquals("multilingual", r.model(), "决策 mock 不得冒用 mock-embedding（索引血缘的合同值）");
        assertEquals(Boolean.TRUE, e.getLastTestOk());
    }

    // ---------------- kind/provider 配对 ----------------

    @Test
    void decisionEndpointRejectsOpenAiCompatProvider() {
        ModelEndpointRequest req = decisionDraft("https://api.example.com/v1", "gpt-4o-mini",
                ModelEndpointEntity.PROVIDER_OPENAI);

        assertThrows(DevMindException.class, () -> service.testDraft(req));

        assertTrue(paths.isEmpty(), "配对校验必须在发出请求前拦住");
    }

    @Test
    void vectorEndpointRejectsLayaProvider() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "草稿", "laya",
                "http://127.0.0.1:8377", null, "bge-m3", 5, null, null, null, null);

        assertThrows(DevMindException.class, () -> service.testDraft(req));

        assertTrue(paths.isEmpty());
    }
}
