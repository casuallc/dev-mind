package com.devmind.copilot.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolSpec;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具注册表（CAP-76 FR-03）：重名/非法名/非法 minRole/schema 无法解析一律启动失败。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final List<String> ROLES = List.of("VIEWER", "DEVELOPER", "ADMIN");

    private final Map<String, PlatformTool> tools;
    private final ObjectMapper mapper;

    public ToolRegistry(List<PlatformTool> tools, ObjectMapper mapper) {
        this.mapper = mapper;
        Map<String, PlatformTool> map = new LinkedHashMap<>();
        for (PlatformTool tool : tools) {
            ToolSpec spec = tool.spec();
            String name = spec == null ? null : spec.name();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalStateException("工具名非法: " + name);
            }
            if (spec.title() == null || spec.title().isBlank()) {
                throw new IllegalStateException("工具 " + name + " 缺少 title");
            }
            if (spec.risk() == null) {
                throw new IllegalStateException("工具 " + name + " 缺少 risk");
            }
            if (spec.minRole() == null || !ROLES.contains(spec.minRole())) {
                throw new IllegalStateException("工具 " + name + " 的 minRole 非法: " + spec.minRole());
            }
            try {
                var schema = mapper.readTree(spec.parametersSchema() == null ? "{}" : spec.parametersSchema());
                if (!schema.isObject()) {
                    throw new IllegalStateException("工具 " + name + " 的 parametersSchema 不是 JSON 对象");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("工具 " + name + " 的 parametersSchema 无法解析", e);
            }
            if (map.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("工具名重复: " + name);
            }
        }
        this.tools = Map.copyOf(map);
    }

    @PostConstruct
    void logTools() {
        log.info("CAP-76 工具注册 {} 个: {}", tools.size(), String.join(",", tools.keySet()));
    }

    public Optional<PlatformTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<PlatformTool> all() {
        return List.copyOf(tools.values());
    }

    /** 模型侧下发：按角色过滤（角色序 VIEWER < DEVELOPER < ADMIN，未知角色按 VIEWER）。 */
    public List<PlatformTool> visibleTo(String role) {
        int rank = rank(role);
        List<PlatformTool> out = new ArrayList<>();
        for (PlatformTool tool : tools.values()) {
            if (rank(tool.spec().minRole()) <= rank) {
                out.add(tool);
            }
        }
        return out;
    }

    public boolean empty() {
        return tools.isEmpty();
    }

    public static int rank(String role) {
        if (role == null) {
            return 1;
        }
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> 3;
            case "DEVELOPER" -> 2;
            default -> 1;
        };
    }

    /** OpenAPI 3.0 契约（描述用，不是可调用端点）。 */
    public Map<String, Object> openApi() {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (PlatformTool tool : tools.values()) {
            ToolSpec spec = tool.spec();
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("operationId", spec.name());
            operation.put("summary", spec.title());
            operation.put("description", spec.description());
            operation.put("x-tool-risk", spec.risk().name());
            operation.put("x-min-role", spec.minRole());
            operation.put("x-owner-module", spec.ownerModule());
            operation.put("x-tool-since", spec.since());
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("application/json", Map.of("schema", schemaOf(spec)));
            operation.put("requestBody", Map.of("required", true, "content", content));
            operation.put("responses", Map.of("200", Map.of("description", "工具结果（ToolResult）")));
            paths.put("/tools/" + spec.name(), Map.of("post", operation));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", Map.of("title", "Dev-Mind 平台智能体工具契约",
                "version", "0.1.0", "description", "由 ToolRegistry 生成；非可调用端点"));
        doc.put("paths", paths);
        return doc;
    }

    private Object schemaOf(ToolSpec spec) {
        try {
            return mapper.readTree(spec.parametersSchema() == null ? "{}" : spec.parametersSchema());
        } catch (Exception e) {
            return Map.of("type", "object");
        }
    }
}
