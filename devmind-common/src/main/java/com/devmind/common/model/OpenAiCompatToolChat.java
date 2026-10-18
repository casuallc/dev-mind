package com.devmind.common.model;

import com.devmind.common.agent.tool.ToolCall;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-76 FR-06 OpenAI 兼容 {@code /chat/completions} + native function calling 客户端。
 *
 * <p>放在 common 的 {@code model} 包内是刻意的：超时/脱敏助手 {@link OpenAiCompatHttp} 是包内可见的，
 * 跨模块无法复用；协议客户端也本就属于 common。</p>
 *
 * <p>约束：不重试（避免重复计费，由上层决定 fallback）；非 2xx 抛带状态码的脱敏异常；
 * {@code function.arguments} 既可能是 JSON 字符串也可能是对象，统一解析。参数不是合法 JSON 时
 * 返回空参数（{@code args} 为空 Map），让 schema 校验给出 {@code INVALID_ARGUMENTS} 回灌模型纠正，
 * 而不是把整轮打挂。</p>
 */
public final class OpenAiCompatToolChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiCompatToolChat() {
    }

    /** 一次调用的全部外部输入。temperature 由配置传入；不主动发 max_tokens（同 OpenAiCompatChat 的理由）。 */
    public record Options(String baseUrl, String apiKey, String model, int timeoutSeconds, double temperature) {
    }

    /** 暴露给模型的工具定义；parameters 是 JSON Schema 字符串。 */
    public record ToolDef(String name, String description, String parametersSchema) {
    }

    /**
     * 一条对话消息。
     *
     * @param role       system / user / assistant / tool
     * @param content    文本正文（tool 消息为工具结果）
     * @param toolCalls  仅 assistant 有：模型上一轮给出的工具调用（回灌时必须原样带回）
     * @param toolCallId 仅 tool 有：对应 {@code tool_calls[].id}
     */
    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public Message {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String content) {
            return new Message("system", content, List.of(), null);
        }

        public static Message user(String content) {
            return new Message("user", content, List.of(), null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, List.of(), null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, List.of(), toolCallId);
        }
    }

    /**
     * 一次模型回复。
     *
     * @param content        正文（可能为空，纯工具调用时）
     * @param toolCalls      本轮工具调用（可能为空）
     * @param finishReason   stop / tool_calls / length / …
     * @param promptTokens   usage 缺失时为 0
     * @param completionTokens usage 缺失时为 0
     * @param totalTokens    usage 缺失时为 0
     */
    public record Completion(
            String content,
            List<ToolCall> toolCalls,
            String finishReason,
            int promptTokens,
            int completionTokens,
            int totalTokens) {

        public Completion {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** 非 2xx：保留状态码与脱敏后的响应片段，供 {@code auto} 模式判定是否降级 json。 */
    public static final class HttpStatusException extends ModelCallException {

        private final int statusCode;
        private final String bodySnippet;

        HttpStatusException(String message, int statusCode, String bodySnippet) {
            super(message);
            this.statusCode = statusCode;
            this.bodySnippet = bodySnippet;
        }

        public int statusCode() {
            return statusCode;
        }

        /** 仅用于模式判定：只认明确"不支持 tools/function calling"的 400。 */
        public boolean toolUnsupported() {
            if (statusCode != 400) {
                return false;
            }
            String b = bodySnippet.toLowerCase();
            boolean hasToolWord = b.contains("tool") || b.contains("function");
            boolean unsupported = b.contains("not support") || b.contains("unsupported")
                    || b.contains("not recognized") || b.contains("unknown field")
                    || b.contains("unknown parameter") || b.contains("unexpected");
            return hasToolWord && unsupported;
        }
    }

    public static Completion complete(Options opt, List<Message> messages, List<ToolDef> tools) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", opt.model());
            root.put("temperature", opt.temperature());
            var arr = root.putArray("messages");
            for (Message m : messages == null ? List.<Message>of() : messages) {
                arr.add(messageNode(m));
            }
            if (tools != null && !tools.isEmpty()) {
                var toolArr = root.putArray("tools");
                for (ToolDef def : tools) {
                    var t = toolArr.addObject();
                    t.put("type", "function");
                    var f = t.putObject("function");
                    f.put("name", def.name());
                    f.put("description", def.description());
                    f.set("parameters", schemaNode(def.parametersSchema()));
                }
                root.put("tool_choice", "auto");
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(
                            opt.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(opt.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)));
            if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
                req.header("Authorization", "Bearer " + opt.apiKey());
            }
            HttpRequest request = req.build();
            HttpResponse<String> resp = OpenAiCompatHttp.http(opt.timeoutSeconds())
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String snippet = OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(resp.body()));
                throw new HttpStatusException(
                        OpenAiCompatHttp.failure("智能体对话端点", request.uri(), resp.statusCode(), resp.body()),
                        resp.statusCode(), snippet);
            }
            return parse(resp.body());
        } catch (ModelCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("智能体对话调用被中断", e);
        } catch (Exception e) {
            throw new ModelCallException("智能体对话调用失败: "
                    + OpenAiCompatHttp.sanitize(String.valueOf(e)), e);
        }
    }

    private static JsonNode messageNode(Message m) {
        var node = MAPPER.createObjectNode();
        node.put("role", m.role());
        if ("tool".equals(m.role())) {
            node.put("tool_call_id", m.toolCallId() == null ? "" : m.toolCallId());
            node.put("content", m.content());
            return node;
        }
        if (m.toolCalls().isEmpty()) {
            node.put("content", m.content());
            return node;
        }
        var calls = node.putArray("tool_calls");
        for (ToolCall call : m.toolCalls()) {
            var c = calls.addObject();
            c.put("id", call.callId());
            c.put("type", "function");
            var f = c.putObject("function");
            f.put("name", call.name());
            f.put("arguments", writeArgs(call.args()));
        }
        if (m.content().isBlank()) {
            node.putNull("content");
        } else {
            node.put("content", m.content());
        }
        return node;
    }

    private static JsonNode schemaNode(String schemaJson) {
        try {
            return MAPPER.readTree(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数 schema 不是合法 JSON", e);
        }
    }

    private static String writeArgs(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Completion parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ModelCallException("智能体对话响应不是合法 JSON: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelCallException("智能体对话响应缺少 choices: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = OpenAiCompatHttp.contentText(message.path("content")).trim();
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            int index = 0;
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText("");
                if (id.isBlank()) {
                    id = "call_" + index;
                }
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                if (name.isBlank()) {
                    index++;
                    continue;
                }
                Map<String, Object> args = argumentsOf(fn.path("arguments"));
                calls.add(new ToolCall(id, name, args));
                index++;
            }
        }
        if (content.isBlank() && calls.isEmpty()) {
            throw new ModelCallException("智能体对话响应为空（网关拦截或限流？）");
        }
        JsonNode usage = root.path("usage");
        return new Completion(
                content,
                calls,
                choice.path("finish_reason").asText(""),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));
    }

    /** arguments 可能是 JSON 字符串、对象，甚至是二次编码的字符串；解析不了就给空参数交给 schema 校验。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(JsonNode arg) {
        try {
            JsonNode node = arg;
            if (node.isTextual()) {
                String text = node.asText("").trim();
                if (text.isEmpty() || "null".equals(text)) {
                    return Map.of();
                }
                node = MAPPER.readTree(text);
                if (node.isTextual()) {
                    node = MAPPER.readTree(node.asText(""));
                }
            }
            if (!node.isObject()) {
                return Map.of();
            }
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
