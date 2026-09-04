package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 把 claude stream-json 的原始 JSON 行解析为统一 {@link SessionEvent}。
 *
 * <p>CLI 事件 schema 随版本可能变化——本类 + {@code CliProcessLauncher} 是仅有的两个接触 CLI 的地方，
 * 变更只改这两处。解析原则：认识的事件结构化提取，不认识的降级为 {@code log} 事件，绝不丢行。</p>
 *
 * <p>一行可产多条事件：assistant 消息按 content blocks 拆成 text（assistant）+ 逐个 tool_use；
 * user 消息里的 tool_result blocks 各产一条 tool_result（纯文本回显跳过——输入可见性由
 * {@code AbstractSessionRuntime.injectInput} 本地 publish 保证）。</p>
 */
@Component
public class CliEventParser {

    private final ObjectMapper mapper;
    private final SessionProperties props;

    public CliEventParser(ObjectMapper mapper, SessionProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 解析一行。非法 JSON（进度日志、警告等）降级为 log 事件；
     * 纯噪音事件（thinking_tokens、用户输入回显）返回空列表，调用方跳过。
     */
    public List<SessionEvent> parse(LongSupplier seq, String line, String source) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        JsonNode node;
        try {
            node = mapper.readTree(trimmed);
        } catch (Exception e) {
            return List.of(SessionEvent.of(seq.getAsLong(), "log", truncate(trimmed), source));
        }
        if (node == null || !node.isObject()) {
            return List.of(SessionEvent.of(seq.getAsLong(), "log", truncate(trimmed), source));
        }
        String type = node.path("type").asText("");
        String subtype = node.path("subtype").asText("");

        switch (type) {
            case "system" -> {
                // thinking_tokens 是 verbose 噪音，跳过；init 保留（含 session_id/model 可追踪）；
                // 其余 subtype（hook/status 等）降级 log，不再刷屏 "init: <id>"
                if ("thinking_tokens".equals(subtype)) {
                    return List.of();
                }
                if (!"init".equals(subtype)) {
                    return List.of(SessionEvent.of(seq.getAsLong(), "log", truncate(trimmed), source));
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("subtype", subtype);
                putIfPresent(payload, "model", node);
                putIfPresent(payload, "sessionId", node, "session_id");
                return List.of(SessionEvent.of(seq.getAsLong(), "system",
                        "init: " + node.path("session_id").asText(""), source, payload));
            }
            case "assistant" -> {
                return parseAssistant(seq, node.get("message"), source);
            }
            case "user" -> {
                return parseUser(seq, node.get("message"), source);
            }
            case "tool_use" -> {
                String name = node.path("name").asText("tool");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("toolUseId", node.path("id").asText(""));
                payload.put("toolInput", node.path("tool_input").isMissingNode() ? "" : node.path("tool_input").toString());
                return List.of(SessionEvent.of(seq.getAsLong(), "tool_use", name, source, payload));
            }
            case "tool_result" -> {
                boolean isError = node.path("is_error").asBoolean(false);
                String content = node.path("content").isArray()
                        ? extractContentBlocks(node.path("content"))
                        : node.path("content").asText("");
                return List.of(SessionEvent.of(seq.getAsLong(), "tool_result", truncate(content), source,
                        Map.of("isError", isError, "toolUseId", node.path("tool_use_id").asText(""))));
            }
            case "stream_event" -> {
                JsonNode ev = node.path("event");
                String evType = ev.path("type").asText("");
                String text = ev.path("delta").path("text").asText("");
                return List.of(SessionEvent.of(seq.getAsLong(), "text_delta", text, source, Map.of("streamType", evType)));
            }
            case "permission_request" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("requestId", node.path("request_id").asText(""));
                payload.put("action", node.path("action").asText(""));
                payload.put("toolName", node.path("tool_name").asText(""));
                payload.put("input", node.path("input").asText(""));
                payload.put("options", node.path("options").toString());
                return List.of(SessionEvent.of(seq.getAsLong(), "permission_request",
                        node.path("tool_name").asText("") + " " + node.path("input").asText(""), source, payload));
            }
            case "permission_result" -> {
                return List.of(SessionEvent.of(seq.getAsLong(), "permission_result",
                        node.path("permission").asText("") + " request=" + node.path("permission_request_id").asText(""),
                        source, Map.of("permission", node.path("permission").asText(""))));
            }
            case "result" -> {
                boolean isError = node.path("is_error").asBoolean(false);
                String result = node.path("result").asText("");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("isError", isError);
                payload.put("subtype", subtype);
                if (node.has("total_cost_usd")) {
                    payload.put("cost", node.path("total_cost_usd").asText());
                }
                if (node.has("duration_ms")) {
                    payload.put("durationMs", node.path("duration_ms").asLong());
                }
                if (node.has("usage")) {
                    payload.put("usage", node.path("usage").toString());
                }
                return List.of(SessionEvent.of(seq.getAsLong(), "result", truncate(result), source, payload));
            }
            case "error" -> {
                return List.of(SessionEvent.of(seq.getAsLong(), "error", node.path("message").asText("unknown"), source));
            }
            default -> {
                return List.of(SessionEvent.of(seq.getAsLong(), "log", truncate(trimmed), source));
            }
        }
    }

    /** assistant 消息：text blocks 合并为一条 assistant；每个 tool_use block 产一条 tool_use。 */
    private List<SessionEvent> parseAssistant(LongSupplier seq, JsonNode message, String source) {
        if (message == null || message.isMissingNode()) {
            return List.of();
        }
        String model = message.path("model").asText("");
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return List.of(assistantEvent(seq, content.asText(), source, model));
        }
        if (!content.isArray()) {
            return List.of(assistantEvent(seq, message.toString(), source, model));
        }
        List<SessionEvent> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode b : content) {
            String t = b.path("type").asText("");
            if ("text".equals(t)) {
                text.append(b.path("text").asText(""));
            } else if ("tool_use".equals(t)) {
                String name = b.path("name").asText("tool");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("toolUseId", b.path("id").asText(""));
                payload.put("toolInput", b.path("input").isMissingNode() ? "" : b.path("input").toString());
                out.add(SessionEvent.of(seq.getAsLong(), "tool_use", name, source, payload));
            }
        }
        if (!text.isEmpty()) {
            out.addFirst(assistantEvent(seq, text.toString(), source, model));
        }
        return out;
    }

    /** user 消息：tool_result blocks 各产一条 tool_result；纯文本是输入回显，跳过。 */
    private List<SessionEvent> parseUser(LongSupplier seq, JsonNode message, String source) {
        if (message == null || message.isMissingNode()) {
            return List.of();
        }
        JsonNode content = message.path("content");
        if (!content.isArray()) {
            return List.of();
        }
        List<SessionEvent> out = new ArrayList<>();
        for (JsonNode b : content) {
            if (!"tool_result".equals(b.path("type").asText(""))) {
                continue;
            }
            JsonNode c = b.path("content");
            String text = c.isArray() ? extractContentBlocks(c) : c.asText("");
            out.add(SessionEvent.of(seq.getAsLong(), "tool_result", truncate(text), source,
                    Map.of("isError", b.path("is_error").asBoolean(false),
                            "toolUseId", b.path("tool_use_id").asText(""))));
        }
        return out;
    }

    private SessionEvent assistantEvent(LongSupplier seq, String text, String source, String model) {
        if (model == null || model.isBlank()) {
            return SessionEvent.of(seq.getAsLong(), "assistant", truncate(text), source);
        }
        return SessionEvent.of(seq.getAsLong(), "assistant", truncate(text), source, Map.of("model", model));
    }

    private void putIfPresent(Map<String, Object> payload, String key, JsonNode node) {
        putIfPresent(payload, key, node, key);
    }

    private void putIfPresent(Map<String, Object> payload, String key, JsonNode node, String field) {
        String v = node.path(field).asText("");
        if (!v.isEmpty()) {
            payload.put(key, v);
        }
    }

    private String extractContentBlocks(JsonNode blocks) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : blocks) {
            String t = b.path("type").asText("");
            if ("text".equals(t)) {
                sb.append(b.path("text").asText(""));
            } else if ("tool_use".equals(t)) {
                sb.append("[").append(b.path("name").asText("tool")).append("] ");
            }
        }
        return sb.toString();
    }

    private String truncate(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int max = props.getMaxEventBytes();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n...[截断 " + (s.length() - max) + " 字符]";
    }
}
