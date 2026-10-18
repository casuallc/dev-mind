package com.devmind.common.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具参数校验（CAP-76 FR-04）：JSON Schema 子集。
 *
 * <p>支持：{@code type}（object/array/string/integer/number/boolean）、{@code properties}、
 * {@code required}、{@code enum}、{@code maxLength}、{@code maxItems}、{@code minimum} /
 * {@code maximum}、{@code default}；未知字段一律拒绝（{@code additionalProperties} 视为 false）。
 * 校验通过后返回只含 schema 声明字段的规范化 Map。</p>
 */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgs() {
    }

    /** 参数不合法：网关映射为 {@link ToolResult#INVALID_ARGUMENTS}。 */
    public static final class InvalidArgumentException extends RuntimeException {
        public InvalidArgumentException(String message) {
            super(message);
        }
    }

    public static Map<String, Object> validate(String schemaJson, Map<String, Object> args) {
        JsonNode schema;
        try {
            schema = MAPPER.readTree(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
        } catch (Exception e) {
            throw new InvalidArgumentException("工具参数 schema 无法解析");
        }
        if (!schema.isObject()) {
            throw new InvalidArgumentException("工具参数 schema 必须是 object");
        }
        if (!"object".equals(schema.path("type").asText("object"))) {
            throw new InvalidArgumentException("工具参数 schema 顶层必须是 object 类型");
        }
        Map<String, Object> input = args == null ? Map.of() : args;
        return validateObject(schema, input, "参数");
    }

    private static Map<String, Object> validateObject(JsonNode schema, Map<String, Object> input, String path) {
        JsonNode props = schema.path("properties");
        Set<String> allowed = new LinkedHashSet<>();
        if (props.isObject()) {
            props.properties().forEach(e -> allowed.add(e.getKey()));
        }
        for (String key : input.keySet()) {
            if (!allowed.contains(key)) {
                throw new InvalidArgumentException(path + "包含未知字段: " + key);
            }
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode r : required) {
                String key = r.asText("");
                if (!input.containsKey(key) || input.get(key) == null) {
                    throw new InvalidArgumentException("缺少必填字段: " + key);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (props.isObject()) {
            for (Map.Entry<String, JsonNode> e : props.properties()) {
                String key = e.getKey();
                JsonNode def = e.getValue();
                Object value = input.get(key);
                if (value == null) {
                    if (def.has("default")) {
                        out.put(key, plain(def.path("default")));
                    }
                    continue;
                }
                out.put(key, validateValue(def, value, key));
            }
        }
        return out;
    }

    private static Object validateValue(JsonNode def, Object value, String path) {
        String type = def.path("type").asText("");
        Object normalized = switch (type) {
            case "string" -> {
                if (!(value instanceof String s)) {
                    throw new InvalidArgumentException(path + " 应为字符串");
                }
                int max = def.path("maxLength").asInt(0);
                if (max > 0 && s.length() > max) {
                    throw new InvalidArgumentException(path + " 超过最大长度 " + max);
                }
                yield s;
            }
            case "integer" -> {
                if (!(value instanceof Number n) || (value instanceof Double d && d != Math.rint(d))
                        || (value instanceof Float f && f != Math.rint(f))) {
                    throw new InvalidArgumentException(path + " 应为整数");
                }
                yield value;
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    throw new InvalidArgumentException(path + " 应为数字");
                }
                yield value;
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    throw new InvalidArgumentException(path + " 应为布尔值");
                }
                yield value;
            }
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    throw new InvalidArgumentException(path + " 应为数组");
                }
                int max = def.path("maxItems").asInt(0);
                if (max > 0 && list.size() > max) {
                    throw new InvalidArgumentException(path + " 最多 " + max + " 项");
                }
                JsonNode itemSchema = def.path("items");
                List<Object> items = new ArrayList<>(list.size());
                for (Object item : list) {
                    items.add(itemSchema.isObject()
                            ? validateValue(itemSchema, item, path + "[]") : item);
                }
                yield items;
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?> m)) {
                    throw new InvalidArgumentException(path + " 应为对象");
                }
                Map<String, Object> typed = new LinkedHashMap<>();
                m.forEach((k, v) -> typed.put(String.valueOf(k), v));
                yield validateObject(def, typed, path + ".");
            }
            case "" -> value;
            default -> throw new InvalidArgumentException(path + " 的 schema 类型不支持: " + type);
        };
        JsonNode enums = def.path("enum");
        if (enums.isArray() && !enums.isEmpty()) {
            for (JsonNode option : enums) {
                if (String.valueOf(normalized).equals(option.asText())) {
                    return normalized;
                }
            }
            throw new InvalidArgumentException(path + " 取值不合法（可选: " + enumOptions(enums) + "）");
        }
        if (normalized instanceof Number n) {
            if (def.has("minimum") && n.doubleValue() < def.path("minimum").asDouble()) {
                throw new InvalidArgumentException(path + " 小于最小值 " + def.path("minimum").asText());
            }
            if (def.has("maximum") && n.doubleValue() > def.path("maximum").asDouble()) {
                throw new InvalidArgumentException(path + " 大于最大值 " + def.path("maximum").asText());
            }
        }
        return normalized;
    }

    private static String enumOptions(JsonNode enums) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode option : enums) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(option.asText());
        }
        return sb.toString();
    }

    /** JsonNode → 普通 Java 值（Jackson 3 用 properties()，不用 fields()）。 */
    private static Object plain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(plain(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), plain(e.getValue())));
            return map;
        }
        return node.asText();
    }
}
