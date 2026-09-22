package com.devmind.common.model;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.devmind.common.decision.DecisionAnswer;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CAP-55 laya 决策边车（{@code tools/laya-sidecar}）的 HTTP 客户端：两个路径 + 一层 wire schema 解析。
 *
 * <p><b>为什么 baseUrl 只填根地址</b>：{@code /healthz} 在根上、{@code /v1/predict} 在 {@code /v1} 下，
 * 路径由本类拼——若让用户填到 {@code /v1}，健康检查就会打到 {@code /v1/healthz} 这个不存在的路径上
 * （与 CAP-48 的 OpenAI 兼容端点"baseUrl 带 /v1"的约定<b>刚好相反</b>，表单里必须写清楚）。</p>
 *
 * <p><b>凭据</b>：边车协议本身没有 apiKey（CAP-55 FR-02），但边车常被放在网关后面，
 * 故 {@code apiKey} 可空、非空时发 {@code Authorization: Bearer}。失败消息一律过
 * {@link OpenAiCompatHttp#sanitize}，与 CAP-48 同口径。</p>
 *
 * <p>本类只做"一次调用"：不重试、不降级——重试与降级是调用方的策略
 * （FR-03 的 {@code HttpDecisionEngine} 一次重试 + degraded，FR-02 的探针由人盯着、不重试）。
 * 失败一律 {@link ModelCallException}：HTTP/传输层失败是 {@link LayaDecisionException}
 * （带状态码，调用方据此判断值不值得重试），应答解析失败（非 JSON / 缺 answers）是普通父类。</p>
 */
public final class LayaDecisionClient {

    /** 边车路径（FR-01）：存活/常驻清单 */
    public static final String PATH_HEALTHZ = "/healthz";
    /** 边车路径（FR-01）：决策调用 */
    public static final String PATH_PREDICT = "/v1/predict";

    /**
     * state 单个字符串值的截断长度（FR-03）。
     *
     * <p>边车侧 multilingual checkpoint 的 state 预算约 768 token（CAP-55 §1），超了会被模型
     * 静默截断——那比"看得见的截断"更糟：调用方不知道自己给的上下文只进去了一半。
     * 按字符截断并留省略标记，至少让截断这件事在 state 里自证。</p>
     */
    public static final int STATE_VALUE_MAX_CHARS = 1500;

    /** 截断标记（会跟着 state 一起进模型，故尽量短且语义明确） */
    static final String TRUNCATED_MARK = "…（已截断）";

    /** state 递归深度上限：state 是调用方给的自由结构，防病态深层嵌套把截断变成遍历炸弹 */
    private static final int STATE_MAX_DEPTH = 4;

    /** 连接测试固定样例的题目 id（FR-02：健康检查之后要实打一次决策往返） */
    public static final String SAMPLE_QUESTION_ID = "sample_choice";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayaDecisionClient() {
    }

    /** 一次调用的全部外部输入；{@code model} 空 = 由边车按语言/routing 自己选 checkpoint */
    public record Options(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    /**
     * {@code /healthz} 应答（FR-02 连接测试第一段）。
     *
     * @param sources 每个槽位的<b>实际来源</b>（CAP-56 FR-01 落地后边车才上报；老版本边车给空 Map）。
     *                它是"这个槽位此刻加载的到底是哪个目录/仓库"的唯一凭据——CAP-56 FR-06 的 serve
     *                自检拿它核对「准入闸门放行的那份 == 正在服务的那份」
     */
    public record Health(String status, String layaVersion, List<String> loaded, Map<String, String> devices,
                         Map<String, SlotSource> sources) {

        public boolean ok() {
            return "ok".equalsIgnoreCase(status);
        }

        /** 给连接测试 message 用的一行摘要（不含设备明细——那是排错时才看的东西） */
        public String summary() {
            String list = loaded == null || loaded.isEmpty()
                    ? "未常驻任何 checkpoint" : "常驻 " + String.join("、", loaded);
            String version = layaVersion == null || layaVersion.isBlank() ? "（版本未知）" : layaVersion;
            return "laya " + version + "，" + list;
        }
    }

    /**
     * {@code /healthz.sources} 里一个槽位的实际来源（CAP-56 FR-01）。
     *
     * <p><b>字段可空表示"边车没报这一项"</b>，不是 false：{@code overridden}/{@code loaded}/{@code ready}
     * 用包装类型，好让"老版本边车没报"与"报了 false"分得开——把前者读成后者就会凭空虚报一条结论。</p>
     *
     * @param source     人读的来源 id（本地绝对路径，或 {@code repo} / {@code repo/subfolder}）
     * @param kind       {@code local}（节点上的目录，微调产物）/ {@code repo}（HF 仓库）
     * @param overridden 与边车内置默认来源是否不同（true = 有人把这个槽位换成了别的产物）
     * @param path       本地目录的绝对路径（kind=local 才有）
     * @param subfolder  仓库子目录（{@code [repo, subfolder]} 形式才有）
     * @param ready       本地目录是否齐备（有 {@code rl_agent_config.json} 与 {@code model.safetensors}）
     * @param missing     {@code ready=false} 时缺的文件名
     */
    public record SlotSource(String source, String kind, Boolean overridden, Boolean loaded,
                             String path, String subfolder, Boolean ready, List<String> missing,
                             String repo, String device) {

        /** 加载的是节点上的目录（微调产物），而不是 HF 仓库 */
        public boolean local() {
            return "local".equalsIgnoreCase(kind);
        }
    }

    /**
     * {@code /v1/predict} 应答的解析产物。
     *
     * @param answers       逐题答案（题 id 原样保留，便于调用方按自己发的题取）
     * @param routingModel  边车实际选中的 checkpoint 别名（可空 = 边车未报）。
     *                      <b>不等于端点配的 model</b>：端点没配 checkpoint 时由边车按语言/脚本自己路由，
     *                      那时这里才是"真实用了谁"，落库与「查看依据」都该用这个值
     * @param routingReason 边车选 checkpoint 的原因（UI「查看依据」展示，可能是空串）
     * @param rawJson       应答原文（落库/导出要原文，重新拼装会丢字段）
     */
    public record Reply(Map<String, DecisionAnswer> answers, String routingModel, String routingReason,
                        String rawJson) {
    }

    /** 存活 + 常驻清单；非 2xx 抛 {@link LayaDecisionException}、非 JSON 抛 {@link ModelCallException} */
    public static Health healthz(Options opt) {
        HttpRequest request = withAuth(HttpRequest.newBuilder(uri(opt.baseUrl(), PATH_HEALTHZ)), opt)
                .timeout(Duration.ofSeconds(opt.timeoutSeconds()))
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonNode root = readJson(send(request, opt, "边车存活检查"), "/healthz 应答");
        List<String> loaded = new ArrayList<>();
        root.path("loaded").forEach(n -> loaded.add(n.asText("")));
        // devices 是 {checkpoint: 设备} 的对象；边车换版本时它可能变形状，取不到就当空，别让健康检查自己 500
        Map<String, String> devices = new LinkedHashMap<>();
        if (root.path("devices").isObject()) {
            ((ObjectNode) root.path("devices")).properties()
                    .forEach(e -> devices.put(e.getKey(), e.getValue().asText("")));
        }
        // sources 同理（CAP-56 FR-01 才有的字段）：老版本边车没有这一项 → 空 Map，
        // 由调用方（serve 自检）按"边车没报"处理，而不是在这里编一份出来
        Map<String, SlotSource> sources = new LinkedHashMap<>();
        if (root.path("sources").isObject()) {
            ((ObjectNode) root.path("sources")).properties()
                    .forEach(entry -> sources.put(entry.getKey(), slotSource(entry.getValue())));
        }
        return new Health(root.path("status").asText(""), root.path("laya_version").asText(""), loaded,
                devices, sources);
    }

    private static SlotSource slotSource(JsonNode node) {
        List<String> missing = new ArrayList<>();
        node.path("missing").forEach(n -> missing.add(n.asText("")));
        return new SlotSource(text(node, "source"), text(node, "kind"), bool(node, "overridden"),
                bool(node, "loaded"), text(node, "path"), text(node, "subfolder"),
                bool(node, "ready"), List.copyOf(missing), text(node, "repo"), text(node, "device"));
    }

    /** 一次决策调用；网络/非 2xx 抛 {@link LayaDecisionException}，答案缺失抛 {@link ModelCallException} */
    public static Reply predict(Options opt, Map<String, Object> state,
                                Map<String, Map<String, Object>> questions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", truncateState(state));
        payload.put("questions", questions == null ? Map.of() : questions);
        if (opt.model() != null && !opt.model().isBlank()) {
            payload.put("model", opt.model());
        }
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ModelCallException("决策请求体序列化失败: "
                    + OpenAiCompatHttp.sanitize(String.valueOf(e)), e);
        }
        HttpRequest request = withAuth(HttpRequest.newBuilder(uri(opt.baseUrl(), PATH_PREDICT)), opt)
                .timeout(Duration.ofSeconds(opt.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return parseReply(send(request, opt, "决策调用"));
    }

    /**
     * 连接测试的固定样例（FR-02）：一题 choice、两个选项。
     *
     * <p>state 用中文是刻意的——真实流量是中文，样例走中文才能真正验到"multilingual checkpoint
     * 能否被选中并读懂"这件事（routing.reason 会说清它选了谁）。</p>
     */
    public static Map<String, Object> sampleState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal", "构建失败时先看 StepRunner 日志 Hub 的最后 200 行，九成的环境类报错在那里");
        return state;
    }

    /** 连接测试的固定样例题目（与 {@link #sampleState()} 配套，题 id 见 {@link #SAMPLE_QUESTION_ID}） */
    public static Map<String, Map<String, Object>> sampleQuestions() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("keep", "值得沉淀进知识库");
        criteria.put("discard", "不值得沉淀");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", "这条经验是否值得沉淀到知识库");
        question.put("criteria", criteria);
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put(SAMPLE_QUESTION_ID, question);
        return questions;
    }

    // ---------------- wire schema ----------------

    /** 应答解析：{@code answers} 缺失/为空算失败（2xx + 空答案几乎总是边车侧的模型没就绪） */
    private static Reply parseReply(String body) {
        JsonNode root = readJson(body, "/v1/predict 应答");
        JsonNode answersNode = root.path("answers");
        if (!answersNode.isObject() || answersNode.isEmpty()) {
            throw new ModelCallException("决策应答缺少 answers: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        ((ObjectNode) answersNode).properties().forEach(e -> answers.put(e.getKey(), answer(e.getValue())));
        JsonNode routing = root.path("routing");
        return new Reply(answers, text(routing, "model"), text(routing, "reason"), body);
    }

    private static DecisionAnswer answer(JsonNode node) {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        if (node.path("probabilities").isObject()) {
            ((ObjectNode) node.path("probabilities")).properties()
                    .forEach(e -> probabilities.put(e.getKey(), e.getValue().asDouble()));
        }
        return new DecisionAnswer(
                node.path("type").asText(""),
                text(node, "choice"),
                number(node, "score"),
                number(node, "noul"),
                number(node, "confidence"),
                probabilities);
    }

    private static JsonNode readJson(String body, String what) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ModelCallException(what + "不是合法 JSON: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    /** 数值字段：缺失或非数值（choice 题的 score 就是 null）→ null，不要用 asDouble() 的 0.0 顶替 */
    private static Double number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    /** 布尔字段：缺失或非布尔 → null（"边车没报"与"报了 false"必须分得开） */
    private static Boolean bool(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isBoolean() ? v.asBoolean() : null;
    }

    // ---------------- HTTP ----------------

    private static URI uri(String baseUrl, String path) {
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    /** 边车协议本身无鉴权；配了凭据就发（边车放在网关后面时用），与 CAP-48 同口径 */
    private static HttpRequest.Builder withAuth(HttpRequest.Builder builder, Options opt) {
        if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + opt.apiKey());
        }
        return builder;
    }

    /** 发请求并取回响应体；非 2xx 转成带地址的诊断消息（消息已脱敏） */
    private static String send(HttpRequest request, Options opt, String what) {
        try {
            HttpResponse<String> resp = OpenAiCompatHttp.http(opt.timeoutSeconds())
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw LayaDecisionException.status(resp.statusCode(),
                        failure(what, request.uri(), resp.statusCode(), resp.body()));
            }
            return resp.body();
        } catch (ModelCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 带 cause 但不带 IO cause：引擎据此判定"有人在喊停"，不重试（见 retryable()）
            throw LayaDecisionException.transport(what + "被中断", e);
        } catch (Exception e) {
            // 连接被拒/超时/读中断都在这里：消息必须脱敏，且不许把 Jackson/HTTP 异常原文漏出去。
            // 带上 URI——连不上时运维第一个要问的就是"你打的哪个地址"（ConnectException 自己不带 URL）。
            throw LayaDecisionException.transport(what + " " + request.uri() + " 失败: "
                    + OpenAiCompatHttp.sanitize(String.valueOf(e)), e);
        }
    }

    /**
     * 非 2xx 的统一诊断串。<b>不复用 {@link OpenAiCompatHttp#failure}</b>：那边的 404 提示是
     * "baseUrl 要带 /v1"，而边车的 baseUrl 恰恰<b>不能</b>带 /v1——照抄会把人引向错的方向。
     */
    private static String failure(String what, URI uri, int status, String body) {
        String hint = status == 404
                ? "（地址下没有这个路径：baseUrl 只填边车根地址，如 http://host:8377，"
                        + "/healthz 与 /v1/predict 由平台拼接）"
                : "";
        return what + " " + uri + " 返回 " + status + ": "
                + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)) + hint;
    }

    // ---------------- state 截断 ----------------

    /**
     * state 值截断（FR-03）：递归到字符串叶子，超长截断并留标记。
     *
     * <p><b>只截值不删键</b>：调用方给的键（proposal / similar_entries …）本身就是模型要参照的
     * schema，删键会让模型面对一个残缺结构；截值则只损失长度，语义还在。</p>
     */
    static Object truncateState(Object state) {
        return truncate(state, STATE_MAX_DEPTH);
    }

    private static Object truncate(Object value, int depth) {
        if (value instanceof CharSequence s) {
            return s.length() <= STATE_VALUE_MAX_CHARS
                    ? s.toString() : s.subSequence(0, STATE_VALUE_MAX_CHARS) + TRUNCATED_MARK;
        }
        if (depth <= 0) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, truncate(v, depth - 1)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(truncate(v, depth - 1)));
            return out;
        }
        return value;
    }
}
