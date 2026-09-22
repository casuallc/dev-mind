package com.devmind.common.model;

import com.devmind.common.decision.DecisionAnswer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-55 决策边车客户端：真起 JDK HttpServer 收请求，验证 /healthz 与 /v1/predict 的连法与
 * wire schema 解析（三原语、routing.reason、原文保留）、state 截断、失败消息可操作且已脱敏。
 */
class LayaDecisionClientTest {

    private static final String API_KEY = "sk-laya-secret-42";
    private static final String BASE_JSON = "{\"model\":\"laya-rl-agent\",\"usage\":{\"input_tokens\":37}}";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个按 path → 响应体分发的假边车；未知路径 404 */
    private String serve(Map<String, String> byPath, int status) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = byPath.getOrDefault(ex.getRequestURI().getPath(),
                    "{\"detail\":\"Not Found\"}");
            respond(ex, byPath.containsKey(ex.getRequestURI().getPath()) ? status : 404, json);
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

    private static LayaDecisionClient.Options opt(String baseUrl) {
        return new LayaDecisionClient.Options(baseUrl, API_KEY, null, 5);
    }

    // ---------------- /healthz ----------------

    @Test
    void healthzParsesLoadedCheckpointsAndDevices() throws IOException {
        String baseUrl = serve(Map.of("/healthz", "{\"status\":\"ok\",\"laya_version\":\"0.3.5\","
                + "\"loaded\":[\"multilingual\",\"typed-decisions\"],"
                + "\"devices\":{\"multilingual\":\"cpu\",\"typed-decisions\":\"cpu\"},"
                + "\"cuda_available\":false}"), 200);

        LayaDecisionClient.Health health = LayaDecisionClient.healthz(opt(baseUrl));

        assertTrue(health.ok());
        assertEquals("0.3.5", health.layaVersion());
        assertEquals(List.of("multilingual", "typed-decisions"), health.loaded());
        assertEquals("cpu", health.devices().get("multilingual"));
        assertEquals("laya 0.3.5，常驻 multilingual、typed-decisions", health.summary());
    }

    @Test
    void healthzNotOkIsParsedRatherThanThrown() throws IOException {
        // 边车进程活着但 checkpoint 还没常驻完：这不是网络失败，探针要能把它单独报出来
        String baseUrl = serve(Map.of("/healthz",
                "{\"status\":\"loading\",\"laya_version\":\"0.3.5\",\"loaded\":[]}"), 200);

        LayaDecisionClient.Health health = LayaDecisionClient.healthz(opt(baseUrl));

        assertFalse(health.ok());
        assertTrue(health.summary().contains("未常驻任何 checkpoint"), health.summary());
    }

    @Test
    void healthzWithoutSourcesYieldsEmptyMapNotFabricatedEntries() throws IOException {
        // FR-01 之前的老版本边车：没有 sources 字段。空 Map 是"边车没报"，不是"来源一致"
        String baseUrl = serve(Map.of("/healthz", "{\"status\":\"ok\",\"loaded\":[\"multilingual\"]}"), 200);

        LayaDecisionClient.Health health = LayaDecisionClient.healthz(opt(baseUrl));

        assertTrue(health.sources().isEmpty(), health.sources().toString());
    }

    @Test
    void healthzParsesSlotSourcesIncludingLocalReadiness() throws IOException {
        String baseUrl = serve(Map.of("/healthz", "{\"status\":\"ok\",\"laya_version\":\"0.3.5\","
                + "\"loaded\":[\"typed-decisions\"],"
                + "\"sources\":{"
                + "\"typed-decisions\":{\"source\":\"D:\\\\apusic\\\\laya\\\\ft7\",\"kind\":\"local\","
                + "\"overridden\":true,\"loaded\":true,\"path\":\"D:\\\\apusic\\\\laya\\\\ft7\","
                + "\"exists\":true,\"ready\":false,\"missing\":[\"model.safetensors\"],\"device\":\"cuda:0\"},"
                + "\"multilingual\":{\"source\":\"convaiinnovations/laya/multilingual\",\"kind\":\"repo\","
                + "\"overridden\":false,\"loaded\":false,\"repo\":\"convaiinnovations/laya\","
                + "\"subfolder\":\"multilingual\"}}}"), 200);

        Map<String, LayaDecisionClient.SlotSource> sources =
                LayaDecisionClient.healthz(opt(baseUrl)).sources();

        LayaDecisionClient.SlotSource ft = sources.get("typed-decisions");
        assertTrue(ft.local());
        assertTrue(ft.overridden());
        assertTrue(ft.loaded());
        assertFalse(ft.ready());                                   // 缺文件 = 没就绪，服务不成
        assertEquals(List.of("model.safetensors"), ft.missing());
        assertEquals("cuda:0", ft.device());
        LayaDecisionClient.SlotSource repo = sources.get("multilingual");
        assertFalse(repo.local());
        assertEquals("convaiinnovations/laya", repo.repo());
        assertEquals("multilingual", repo.subfolder());
        assertNull(repo.ready());                                  // 仓库来源没有 ready 这一说，不许编成 false
    }

    // ---------------- /v1/predict ----------------

    @Test
    void predictParsesAllThreePrimitivesAndKeepsRawJson() throws IOException {
        String answers = "{\"adopt_layer\":{\"type\":\"choice\",\"choice\":\"project\","
                + "\"probabilities\":{\"global\":0.04,\"project\":0.95,\"discard\":0.01},"
                + "\"confidence\":0.95,\"action\":{\"act_probability\":0.95}},"
                + "\"is_duplicate\":{\"type\":\"noul\",\"noul\":0.14,\"confidence\":0.6},"
                + "\"quality\":{\"type\":\"score\",\"score\":0.7,\"legend\":{\"0\":\"含糊\"},"
                + "\"probabilities\":{\"0\":0.1,\"1\":0.6,\"2\":0.3},\"confidence\":0.5}}";
        String routing = "{\"model\":\"multilingual\",\"reason\":\"non-Latin script (han)\",\"repo\":\"laya\"}";
        String baseUrl = serve(Map.of("/v1/predict", response(answers, routing)), 200);

        LayaDecisionClient.Reply reply = LayaDecisionClient.predict(opt(baseUrl),
                Map.of("proposal", "构建失败先看日志 Hub"), questionsStub());

        DecisionAnswer choice = reply.answers().get("adopt_layer");
        assertEquals("choice", choice.type());
        assertEquals("project", choice.choice());
        assertEquals(0.95, choice.choiceProbability());
        assertEquals(0.95, choice.confidence());
        assertNull(choice.score(), "choice 题没有 score");
        assertNull(choice.noul());

        assertEquals(0.14, reply.answers().get("is_duplicate").noul());
        assertEquals(0.7, reply.answers().get("quality").score());
        assertEquals(0.6, reply.answers().get("quality").probabilities().get("1"),
                "score 题的概率按下标为键");
        assertEquals("non-Latin script (han)", reply.routingReason());
        assertTrue(reply.rawJson().contains("action"), "原文要留着，重新拼装会丢字段");
    }

    @Test
    void predictPostsStateAndQuestionsAndOmitsBlankModel() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict", singleAnswer()), 200);

        LayaDecisionClient.predict(new LayaDecisionClient.Options(baseUrl, API_KEY, "  ", 5),
                Map.of("proposal", "中文提案"), questionsStub());

        assertEquals(List.of("/v1/predict"), paths);
        String body = bodies.get(0);
        assertTrue(body.contains("中文提案"), body);
        assertFalse(body.contains("\"model\""), "model 空 = 由边车自己路由，请求体里不该出现该键: " + body);
        assertEquals("Bearer " + API_KEY, authHeader.get());
    }

    @Test
    void predictSendsExplicitCheckpointAndSkipsAuthHeaderWithoutKey() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict", singleAnswer()), 200);

        LayaDecisionClient.predict(new LayaDecisionClient.Options(baseUrl, null, "multilingual", 5),
                Map.of("proposal", "x"), questionsStub());

        assertTrue(bodies.get(0).contains("\"model\":\"multilingual\""), bodies.get(0));
        assertNull(authHeader.get(), "无凭据就不该发 Authorization（边车协议本无鉴权）");
    }

    /** 2xx 但答案为空：几乎总是边车侧模型没就绪，必须算失败而不是"连通" */
    @Test
    void predictWithoutAnswersIsAFailure() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict", "{\"model\":\"laya-rl-agent\"}"), 200);

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> LayaDecisionClient.predict(opt(baseUrl), Map.of("proposal", "x"), questionsStub()));

        assertTrue(ex.getMessage().contains("answers"), ex.getMessage());
    }

    /** answers 是空对象与缺 answers 等价：2xx + 空答案 = 模型没就绪，不能当连通 */
    @Test
    void predictWithEmptyAnswersObjectIsAFailureToo() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict", emptyAnswers()), 200);

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> LayaDecisionClient.predict(opt(baseUrl), Map.of("proposal", "x"), questionsStub()));

        assertTrue(ex.getMessage().contains("answers"), ex.getMessage());
    }

    @Test
    void predictFailureCarriesAddressAndSanitizesSecret() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict",
                "{\"error\":\"rejected Bearer " + API_KEY + "\"}"), 500);

        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> LayaDecisionClient.predict(opt(baseUrl), Map.of("proposal", "x"), questionsStub()));

        assertTrue(ex.getMessage().contains("/v1/predict"), ex.getMessage());
        assertFalse(ex.getMessage().contains(API_KEY), ex.getMessage());
        assertTrue(ex.getMessage().contains("***"), ex.getMessage());
    }

    @Test
    void predict404HintPointsAtRootBaseUrlInsteadOfMissingV1() throws IOException {
        String baseUrl = serve(Map.of("/v1/predict", "{}"), 200);

        // baseUrl 多带了 /v1（OpenAI 兼容的习惯）：真实路径变成 /v1/v1/predict → 404
        ModelCallException ex = assertThrows(ModelCallException.class,
                () -> LayaDecisionClient.predict(new LayaDecisionClient.Options(
                        baseUrl + "/v1", null, null, 5), Map.of("proposal", "x"), questionsStub()));

        assertTrue(ex.getMessage().contains("根地址"), ex.getMessage());
        assertFalse(ex.getMessage().contains("baseUrl 带 /v1 前缀"), "边车的提示不能抄 OpenAI 那条: "
                + ex.getMessage());
    }

    // ---------------- state 截断（FR-03） ----------------

    @Test
    void truncatesLongStateValuesButKeepsKeysAndNonStrings() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal", "长".repeat(3000));
        state.put("similar_entries", List.of("条".repeat(2000), "短条目"));
        state.put("attempt", 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> truncated = (Map<String, Object>) LayaDecisionClient.truncateState(state);

        assertEquals(LayaDecisionClient.STATE_VALUE_MAX_CHARS + LayaDecisionClient.TRUNCATED_MARK.length(),
                ((String) truncated.get("proposal")).length());
        assertTrue(((String) truncated.get("proposal")).endsWith(LayaDecisionClient.TRUNCATED_MARK));
        assertTrue(truncated.containsKey("similar_entries"), "截值不删键：键是模型要参照的 schema");
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) truncated.get("similar_entries");
        assertTrue(((String) entries.get(0)).endsWith(LayaDecisionClient.TRUNCATED_MARK));
        assertEquals("短条目", entries.get(1), "没超长的值原样保留");
        assertEquals(3, truncated.get("attempt"), "非字符串值不参与截断");
    }

    // ---------------- 固定样例（FR-02 连接测试用） ----------------

    @Test
    void sampleQuestionIsAValidChoiceSchemaWithSmallState() {
        Map<String, Map<String, Object>> questions = LayaDecisionClient.sampleQuestions();
        Map<String, Object> question = questions.get(LayaDecisionClient.SAMPLE_QUESTION_ID);

        assertEquals("choice", question.get("type"));
        assertTrue(question.get("criteria") instanceof Map, "choice 题的 criteria 必须是选项→描述");
        assertEquals(2, ((Map<?, ?>) question.get("criteria")).size());
        assertTrue(LayaDecisionClient.sampleState().get("proposal") instanceof String);
        assertTrue(questions.keySet().stream().allMatch(LayaDecisionClient.SAMPLE_QUESTION_ID::equals),
                "样例只发一题，别让它变成慢探针");
    }

    private static Map<String, Map<String, Object>> questionsStub() {
        return LayaDecisionClient.sampleQuestions();
    }

    /** 按边车真实应答形状拼响应：model/usage 是 laya 会带的兄弟字段，解析不能因为它们在就出岔子 */
    private static String response(String answersJson, String routingJson) {
        return "{\"model\":\"laya-rl-agent\",\"answers\":" + answersJson
                + ",\"routing\":" + routingJson + ",\"usage\":{\"input_tokens\":37,\"output_tokens\":0}}";
    }

    private static String emptyAnswers() {
        return response("{}", "{}");
    }

    /** 一条最小可解析的答案（给只关心请求体的用例用） */
    private static String singleAnswer() {
        return response("{\"sample_choice\":{\"type\":\"choice\",\"choice\":\"keep\","
                + "\"probabilities\":{\"keep\":0.9,\"discard\":0.1},\"confidence\":0.9}}", "{}");
    }
}
