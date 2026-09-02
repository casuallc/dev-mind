package com.devmind.integration.connector.jira;

import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Jira /rest/api/2/search 响应 → IssuePage 的纯函数映射（无 HTTP，单测喂 fixture）。
 * Jira Server/DC 的 description 是 wiki 纯文本（非 Cloud 的 ADF），时间格式为
 * ISO 偏移无冒号（2024-01-02T03:04:05.000+0800）。
 */
public final class JiraIssueMapper {

    /** Jira Server 时间格式：yyyy-MM-dd'T'HH:mm:ss.SSSZ（偏移无冒号） */
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private JiraIssueMapper() {
    }

    public static IssuePage toPage(JsonNode body) {
        if (body == null || !body.isObject()) {
            return new IssuePage(0, 0, 0, List.of());
        }
        List<JiraIssue> issues = new ArrayList<>();
        JsonNode arr = body.get("issues");
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                JiraIssue issue = toIssue(node);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        }
        return new IssuePage(
                body.path("startAt").asInt(0),
                body.path("maxResults").asInt(issues.size()),
                body.path("total").asInt(issues.size()),
                issues);
    }

    /** 单个 issue；缺 key 视为脏数据跳过（返回 null） */
    static JiraIssue toIssue(JsonNode node) {
        String key = text(node, "key");
        if (key == null) {
            return null;
        }
        JsonNode f = node.get("fields");
        return new JiraIssue(
                key,
                text(f, "summary"),
                text(f, "description"),
                nestedText(f, "issuetype", "name"),
                nestedText(f, "priority", "name"),
                labels(f),
                nestedText(f, "status", "name"),
                parseTime(text(f, "created")),
                parseTime(text(f, "updated")),
                userName(f, "reporter"),
                userName(f, "assignee"),
                parseDate(text(f, "duedate")),
                versionNames(f));
    }

    /** 用户对象取显示名：displayName 优先，回退 name（部分实例无 displayName）；未指派（null）返回 null */
    private static String userName(JsonNode fields, String object) {
        String displayName = nestedText(fields, object, "displayName");
        return displayName != null ? displayName : nestedText(fields, object, "name");
    }

    /** fixVersions 是对象数组 [{"name":"1.0",...}]，取 name 拼接；缺失返回空表 */
    private static List<String> versionNames(JsonNode fields) {
        JsonNode arr = fields == null ? null : fields.get("fixVersions");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            String name = text(v, "name");
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    /** 解析 Jira 日期（yyyy-MM-dd）；解析失败返回 null（与 parseTime 同款容错） */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** fields 下的子对象取 name/displayName 等字段；子对象缺失或为 null（Jira 常把未设字段返回为 null）返回 null */
    private static String nestedText(JsonNode fields, String object, String field) {
        if (fields == null) {
            return null;
        }
        JsonNode o = fields.get(object);
        if (o == null || !o.isObject()) {
            return null;
        }
        return text(o, field);
    }

    private static List<String> labels(JsonNode fields) {
        JsonNode arr = fields == null ? null : fields.get("labels");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            if (v.isTextual()) {
                out.add(v.asText());
            }
        }
        return out;
    }

    /** 解析 Jira 时间；解析失败返回 null（不让单条脏数据炸掉整页） */
    static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, JIRA_TIME).toInstant();
        } catch (Exception e) {
            try {
                // 兜底：标准 ISO（带冒号偏移，部分版本/插件会返回）
                return OffsetDateTime.parse(raw).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 水印 → JQL 时间字面量（"yyyy/MM/dd HH:mm"，JQL 服务端时区语义） */
    public static String toJqlTimeLiteral(Instant watermark) {
        return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                .withZone(ZoneOffset.systemDefault())
                .format(watermark);
    }
}
