package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 claude stream-json 的原始 JSON 行解析为统一 {@link SessionEvent}。
 *
 * <p>CLI 事件 schema 随版本可能变化——本类 + {@code CliProcessLauncher} 是仅有的两个接触 CLI 的地方，
 * 变更只改这两处。解析原则：认识的事件结构化提取，不认识的降级为 {@code log} 事件，绝不丢行。</p>
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
     * 纯噪音事件（如 thinking_tokens）返回 null，调用方跳过。
     */
    public SessionEvent parse(long seq, String line, String source) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) {
            return SessionEvent.of(seq, "log", "", source);
        }
        JsonNode node;
        try {
            node = mapper.readTree(trimmed);
        } catch (Exception e) {
            return SessionEvent.of(seq, "log", truncate(trimmed), source);
        }
        if (node == null || !node.isObject()) {
            return SessionEvent.of(seq, "log", truncate(trimmed), source);
        }
        String type = node.path("type").asText("");
        String subtype = node.path("subtype").asText("");

        switch (type) {
            case "system" -> {
                // thinking_tokens 是 verbose 噪音，跳过；init 保留（含 session_id 可追踪）
                if ("thinking_tokens".equals(subtype)) {
                    return null;
                }
                return SessionEvent.of(seq, "system",
                        "init: " + node.path("session_id").asText(""),
                        source, Map.of("subtype", subtype));
            }
            case "assistant" -> {
                String text = extractText(node.get("message"));
                return SessionEvent.of(seq, "assistant", text, source);
            }
            case "user" -> {
                // claude 回显用户消息；输入可见性由 SessionRuntime.injectInput 本地 publish 保证，这里跳过避免重复
                return null;
            }
            case "tool_use" -> {
                String name = node.path("name").asText("tool");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("name", name);
                payload.put("toolInput", node.path("tool_input").isMissingNode() ? "" : node.path("tool_input").toString());
                return SessionEvent.of(seq, "tool_use", name, source, payload);
            }
            case "tool_result" -> {
                boolean isError = node.path("is_error").asBoolean(false);
                String content = node.path("content").isArray()
                        ? extractContentBlocks(node.path("content"))
                        : node.path("content").asText("");
                return SessionEvent.of(seq, "tool_result", truncate(content), source,
                        Map.of("isError", isError, "toolUseId", node.path("tool_use_id").asText("")));
            }
            case "stream_event" -> {
                JsonNode ev = node.path("event");
                String evType = ev.path("type").asText("");
                String text = ev.path("delta").path("text").asText("");
                return SessionEvent.of(seq, "text_delta", text, source, Map.of("streamType", evType));
            }
            case "permission_request" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("requestId", node.path("request_id").asText(""));
                payload.put("action", node.path("action").asText(""));
                payload.put("toolName", node.path("tool_name").asText(""));
                payload.put("input", node.path("input").asText(""));
                payload.put("options", node.path("options").toString());
                return SessionEvent.of(seq, "permission_request",
                        node.path("tool_name").asText("") + " " + node.path("input").asText(""), source, payload);
            }
            case "permission_result" -> {
                return SessionEvent.of(seq, "permission_result",
                        node.path("permission").asText("") + " request=" + node.path("permission_request_id").asText(""),
                        source, Map.of("permission", node.path("permission").asText("")));
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
                if (node.has("usage")) {
                    payload.put("usage", node.path("usage").toString());
                }
                return SessionEvent.of(seq, "result", truncate(result), source, payload);
            }
            case "error" -> {
                return SessionEvent.of(seq, "error", node.path("message").asText("unknown"), source);
            }
            default -> {
                return SessionEvent.of(seq, "log", truncate(trimmed), source);
            }
        }
    }

    private String extractText(JsonNode message) {
        if (message == null || message.isMissingNode()) {
            return "";
        }
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return truncate(content.asText());
        }
        if (content.isArray()) {
            return truncate(extractContentBlocks(content));
        }
        return truncate(message.toString());
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
