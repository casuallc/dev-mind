package com.devmind.security.parse;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SARIF 2.1.0 通用解析（CAP-66 FR-02）：semgrep 与 gitleaks 共用。
 * 提取 ruleId/level/message/代码定位（uri+startLine+snippet）；规则表（driver.rules）
 * 的 defaultConfiguration.level 作为 result.level 缺省回落。
 */
public final class SarifParser {

    /** 单条 SARIF result 归一化中间态（引擎无关） */
    public record SarifResult(String ruleId, String level, String message,
                              String uri, Integer startLine, String snippet,
                              Map<String, Object> extra) {
    }

    private SarifParser() {
    }

    public static List<SarifResult> parse(byte[] sarif, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(sarif);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SARIF 解析失败（非合法 JSON）: " + e.getMessage());
        }
        List<SarifResult> out = new ArrayList<>();
        for (JsonNode run : root.path("runs")) {
            Map<String, JsonNode> rules = new LinkedHashMap<>();
            for (JsonNode rule : run.path("tool").path("driver").path("rules")) {
                String id = rule.path("id").asText("");
                if (!id.isBlank()) {
                    rules.put(id, rule);
                }
            }
            String toolName = run.path("tool").path("driver").path("name").asText("");
            for (JsonNode result : run.path("results")) {
                String ruleId = result.path("ruleId").asText(
                        result.path("rule").path("id").asText(""));
                JsonNode rule = rules.get(ruleId);
                String level = result.path("level").asText("");
                if (level.isBlank() && rule != null) {
                    level = rule.path("defaultConfiguration").path("level").asText("");
                }
                if (level.isBlank()) {
                    level = "warning";
                }
                String message = result.path("message").path("text").asText("");
                String uri = null;
                Integer startLine = null;
                String snippet = null;
                JsonNode loc = result.path("locations").isArray() && !result.path("locations").isEmpty()
                        ? result.path("locations").get(0).path("physicalLocation") : null;
                if (loc != null) {
                    uri = loc.path("artifactLocation").path("uri").asText(null);
                    if (loc.path("region").path("startLine").isInt()) {
                        startLine = loc.path("region").path("startLine").intValue();
                    }
                    String sn = loc.path("region").path("snippet").path("text").asText(null);
                    snippet = sn == null ? null : sn.strip();
                }
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("tool", toolName);
                if (rule != null) {
                    String shortDesc = rule.path("shortDescription").path("text").asText("");
                    if (!shortDesc.isBlank()) {
                        extra.put("ruleDescription", shortDesc);
                    }
                    String helpUri = rule.path("helpUri").asText("");
                    if (!helpUri.isBlank()) {
                        extra.put("ruleHelpUri", helpUri);
                    }
                }
                out.add(new SarifResult(ruleId, level, message, normalizeUri(uri), startLine, snippet, extra));
            }
        }
        return out;
    }

    /** 引擎 level → 统一严重度（FR-02 映射表）：error→HIGH、warning→MEDIUM、note→LOW、其余→INFO */
    public static String mapLevel(String level) {
        return switch (level == null ? "" : level.toLowerCase()) {
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "note" -> "LOW";
            default -> "INFO";
        };
    }

    /** 仓库相对路径归一（去 ./ 与盘符前缀、反斜杠转正） */
    public static String normalizeUri(String uri) {
        if (uri == null) {
            return null;
        }
        String u = uri.replace('\\', '/');
        while (u.startsWith("./")) {
            u = u.substring(2);
        }
        return u;
    }
}
