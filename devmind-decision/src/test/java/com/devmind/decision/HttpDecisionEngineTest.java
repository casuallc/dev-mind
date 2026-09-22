package com.devmind.decision;

import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.decision.config.DecisionProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-55 FR-03 决策引擎：真起 JDK HttpServer 当假边车，盯住三件事——
 * 端点解析（没配/类型不对/不完整一律降级）、重试口径（连不上与 5xx 重试一次、
 * 4xx 与解析失败不重试）、以及"永不上抛"这条硬契约。
 */
class HttpDecisionEngineTest {

    private static final String PREDICT_OK =
            "{\"model\":\"laya-rl-agent\",\"answers\":{\"adopt_layer\":{\"type\":\"choice\","
                    + "\"choice\":\"project\",\"probabilities\":{\"global\":0.05,\"project\":0.9,"
                    + "\"discard\":0.05},\"confidence\":0.9},\"duplicate\":{\"type\":\"noul\","
                    + "\"noul\":0.12,\"confidence\":0.8}},\"routing\":{\"model\":\"multilingual\","
                    + "\"reason\":\"non-Latin script (han, 65% of letters)\"},"
                    + "\"usage\":{\"input_tokens\":42}}";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    /** 前 N 次 predict 先按 {@link #failFirst} 失败，用来观察重试 */
    private final AtomicInteger predicts = new AtomicInteger();
    private int failFirst = 0;
    private int failStatus = 500;
    private boolean failTransport = false;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------------- 假边车 ----------------

    private String serve() throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            paths.add(path);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!"/v1/predict".equals(path)) {
                respond(ex, 404, "{\"detail\":\"Not Found\"}");
                return;
            }
            int n = predicts.incrementAndGet();
            if (failTransport && n <= failFirst) {
                // 不发响应直接断开：客户端拿到的是 IO 异常（连接层抖动）
                ex.close();
                return;
            }
            if (n <= failFirst) {
                respond(ex, failStatus, "{\"detail\":\"边车内部错误\"}");
                return;
            }
            respond(ex, 200, PREDICT_OK);
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

    // ---------------- 装配 ----------------

    private static DecisionProperties props(int timeoutSeconds, int retryCount) {
        DecisionProperties p = new DecisionProperties();
        p.setTimeoutSeconds(timeoutSeconds);
        p.setRetryCount(retryCount);
        p.setRetryBackoffMillis(10);
        return p;
    }

    /** 端点解析 SPI 的打桩：只提供"平台默认 DECISION 端点"这一条路径 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModelEndpointProvider> providerOf(ModelEndpointView ep) {
        ModelEndpointProvider provider = mock(ModelEndpointProvider.class);
        when(provider.defaultEndpoint(anyString()))
                .thenReturn(ep == null ? Optional.empty() : Optional.of(ep));
        ObjectProvider<ModelEndpointProvider> provider_of = mock(ObjectProvider.class);
        when(provider_of.getIfAvailable()).thenReturn(provider);
        return provider_of;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModelEndpointProvider> noProviderAtAll() {
        ObjectProvider<ModelEndpointProvider> provider_of = mock(ObjectProvider.class);
        when(provider_of.getIfAvailable()).thenReturn(null);
        return provider_of;
    }

    private static ModelEndpointView decision(String baseUrl, String model) {
        return new ModelEndpointView(7L, ModelEndpointView.KIND_DECISION,
                ModelEndpointView.PROVIDER_LAYA, "laya 决策边车", baseUrl, null, model,
                null, 5, 32, null, null);
    }

    private static DecisionEngine engine(ObjectProvider<ModelEndpointProvider> docProvider,
                                         DecisionProperties props) {
        return new HttpDecisionEngine(docProvider, props);
    }

    private static Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal", "构建失败时先看 StepRunner 日志 Hub 的最后 200 行");
        return state;
    }

    private static Map<String, Map<String, Object>> questions() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("global", "全平台通用");
        criteria.put("project", "只对本项目成立");
        criteria.put("discard", "不值得沉淀");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "choice");
        q.put("instructions", "这条经验该采纳到哪一层");
        q.put("criteria", criteria);
        Map<String, Map<String, Object>> qs = new LinkedHashMap<>();
        qs.put("adopt_layer", q);
        return qs;
    }

    // ---------------- 成功路径 ----------------

    @Test
    void decideReturnsAnswersAndRouting() throws IOException {
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 1));

        DecisionResult r = engine.decide(state(), questions());

        assertFalse(r.degraded(), r.degradedReason());
        assertEquals(2, r.answers().size());
        assertEquals("project", r.answer("adopt_layer").choice());
        assertEquals(0.9, r.answer("adopt_layer").choiceProbability());
        assertEquals(0.12, r.answer("duplicate").noul());
        assertEquals("multilingual", r.routingModel(), "端点没配 checkpoint 时，落库的该是边车实际选中的那个");
        assertTrue(r.routingReason().contains("non-Latin script"));
        assertTrue(r.hasAnswer("adopt_layer") && !r.hasAnswer("missing"));
        assertNull(r.answer("missing"), "没这题不抛异常，返回 null");
        assertTrue(r.latencyMs() >= 0);
        assertEquals(List.of("/v1/predict"), paths, "引擎不预打 /healthz（连接测试才两段实调）");
    }

    @Test
    void configuredCheckpointIsSentAsModel() throws IOException {
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, "typed-decisions")), props(3, 0));

        assertFalse(engine.decide(state(), questions()).degraded());

        assertTrue(bodies.get(0).contains("\"model\":\"typed-decisions\""), bodies.get(0));
    }

    @Test
    void oversizedStateIsTruncatedBeforeItLeaves() throws IOException {
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 0));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal", "中文".repeat(2000));

        assertFalse(engine.decide(state, questions()).degraded());

        assertTrue(bodies.get(0).contains("已截断"), "1500 字符/值的截断由客户端落实，引擎契约里已承诺");
        assertTrue(bodies.get(0).length() < 3000, "截断后不该还是原样大小: " + bodies.get(0).length());
    }

    // ---------------- 重试口径 ----------------

    @Test
    void serverErrorIsRetriedOnceThenSucceeds() throws IOException {
        failFirst = 1;
        failStatus = 503;
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 1));

        DecisionResult r = engine.decide(state(), questions());

        assertFalse(r.degraded(), r.degradedReason());
        assertEquals(2, predicts.get(), "5xx 属于'看着像抖'，重试一次");
    }

    @Test
    void transportFailureIsRetriedOnceThenSucceeds() throws IOException {
        failFirst = 1;
        failTransport = true;
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 1));

        DecisionResult r = engine.decide(state(), questions());

        assertFalse(r.degraded(), r.degradedReason());
        assertEquals(2, predicts.get(), "连接层抖动重试一次");
    }

    @Test
    void clientErrorIsNotRetried() throws IOException {
        failFirst = 1;
        failStatus = 400;
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, "multi-lingual")), props(3, 3));

        DecisionResult r = engine.decide(state(), questions());

        assertTrue(r.degraded());
        assertEquals(1, predicts.get(), "4xx 重试多少次都是同一结果，只把延迟翻倍");
        assertTrue(r.degradedReason().contains("400"), r.degradedReason());
    }

    @Test
    void retriesExhaustedThenDegrades() throws IOException {
        failFirst = Integer.MAX_VALUE;
        failStatus = 500;
        String url = serve();
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 2));

        DecisionResult r = engine.decide(state(), questions());

        assertTrue(r.degraded());
        assertEquals(3, predicts.get(), "1 次 + 2 次重试");
        assertTrue(r.answers().isEmpty());
        assertTrue(r.degradedReason().contains("500"), r.degradedReason());
    }

    @Test
    void unparsableBodyDegradesWithoutRetry() throws IOException {
        String url = serve();
        server.removeContext("/");
        server.createContext("/", ex -> respond(ex, 200, "<html>502 Bad Gateway</html>"));
        DecisionEngine engine = engine(providerOf(decision(url, null)), props(3, 1));

        DecisionResult r = engine.decide(state(), questions());

        assertTrue(r.degraded());
        assertTrue(r.degradedReason().contains("不是合法 JSON"), r.degradedReason());
    }

    @Test
    void configuredTimeoutIsApplied() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, PREDICT_OK);
        });
        slow.start();
        server = slow;
        DecisionEngine engine = engine(
                providerOf(decision("http://127.0.0.1:" + slow.getAddress().getPort(), null)),
                props(1, 0));

        long t0 = System.nanoTime();
        DecisionResult r = engine.decide(state(), questions());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(r.degraded(), "1 秒超时打 3 秒的边车必降级");
        assertTrue(elapsedMs < 2500, "配的超时必须真的生效（否则是在等默认值）: " + elapsedMs + " ms");
    }

    // ---------------- 降级链（配置侧） ----------------

    @Test
    void noEndpointDegradesWithoutAnyCall() {
        DecisionResult r = engine(providerOf(null), props(3, 1)).decide(state(), questions());

        assertTrue(r.degraded());
        assertTrue(r.degradedReason().contains("模型接入"), r.degradedReason());
        assertTrue(r.degradedReason().contains("决策"), r.degradedReason());
        assertTrue(paths.isEmpty());
        assertTrue(r.answers().isEmpty(), "降级结果的 answers 是空 map，不是 null");
    }

    @Test
    void missingEngineModuleDegrades() {
        DecisionResult r = engine(noProviderAtAll(), props(3, 1)).decide(state(), questions());

        assertTrue(r.degraded());
        assertTrue(r.degradedReason().contains("未装配"), r.degradedReason());
    }

    @Test
    void nonDecisionDefaultIsIgnored() {
        // 平台默认是 CHAT 端点（defaultEndpoint 的实现被桩成"不管 kind 都返回它"）：
        // 消费方必须自己按 kind 过滤，否则会拿对话端点去打 /v1/predict
        ModelEndpointView chat = new ModelEndpointView(9L, ModelEndpointView.KIND_CHAT,
                ModelEndpointView.PROVIDER_OPENAI, "对话端点", "http://127.0.0.1:1/v1", null,
                "gpt-4o-mini", null, 5, 32, null, null);

        DecisionResult r = engine(providerOf(chat), props(3, 1)).decide(state(), questions());

        assertTrue(r.degraded());
        assertTrue(paths.isEmpty(), "类型不符的端点一个请求都不许发");
    }

    @Test
    void mockEndpointDegradesWithItsOwnReason() {
        ModelEndpointView mocked = new ModelEndpointView(11L, ModelEndpointView.KIND_DECISION,
                ModelEndpointView.PROVIDER_MOCK, "假决策", null, null, "multilingual",
                null, 5, 32, null, null);

        DecisionEngine engine = engine(providerOf(mocked), props(3, 1));

        DecisionResult r = engine.decide(state(), questions());

        assertTrue(r.degraded());
        assertTrue(r.degradedReason().contains("mock"), r.degradedReason());
        assertTrue(r.degradedReason().contains("连接测试"), r.degradedReason());
        assertTrue(engine.unavailableReason().isPresent());
    }

    // ---------------- 配置侧可用性（FR-07 置灰用） ----------------

    @Test
    void unavailableReasonIsEmptyOnlyWhenEndpointIsUsable() throws IOException {
        String url = serve();
        DecisionEngine ready = engine(providerOf(decision(url, null)), props(3, 1));
        DecisionEngine noEndpoint = engine(providerOf(null), props(3, 1));
        DecisionEngine noModule = engine(noProviderAtAll(), props(3, 1));

        assertTrue(ready.unavailableReason().isEmpty(), "配好了就不该拦住入口");
        assertTrue(noEndpoint.unavailableReason().orElse("").contains("模型接入"));
        assertTrue(noModule.unavailableReason().orElse("").contains("未装配"));
        assertNotNull(ready);
        assertTrue(paths.isEmpty(), "可用性判断只读配置，不探活");
    }
}
