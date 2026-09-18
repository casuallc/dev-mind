package com.devmind.integration.connector.jira;

import com.devmind.integration.connector.IntegrationConnector.CreateFieldRef;
import com.devmind.integration.connector.IntegrationConnector.FieldOption;
import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.connector.IntegrationConnector.IssueRef;
import com.devmind.integration.connector.IntegrationConnector.IssueTransition;
import com.devmind.integration.connector.IntegrationConnector.IssueTypeRef;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import com.devmind.integration.connector.IntegrationConnector.PriorityRef;
import com.devmind.integration.connector.IntegrationConnector.UserRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /** GET /issue/{key}/transitions 响应 → 转换清单；缺 id 的脏条目跳过（无法回传执行） */
    public static List<IssueTransition> toTransitions(JsonNode body) {
        JsonNode arr = body == null ? null : body.get("transitions");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<IssueTransition> out = new ArrayList<>();
        for (JsonNode t : arr) {
            String id = text(t, "id");
            if (id != null) {
                out.add(new IssueTransition(id, text(t, "name"), nestedText(t, "to", "name")));
            }
        }
        return out;
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
                versionNames(f),
                seconds(f, "timeoriginalestimate"),
                seconds(f, "timespent"));
    }

    /**
     * CAP-19 FR-09：issue fields 下的 attachment 数组按文件名精确匹配（描述 wiki 标记
     * {@code !name.png!} 按文件名引用附件），返回内容直链与 mime；数组缺失/未命中返回 null。
     * 同名附件（Jira 允许）取首个，与 Jira 自身 wiki 渲染行为一致。
     */
    static AttachmentRef toAttachmentRef(JsonNode issueFields, String filename) {
        if (issueFields == null || filename == null || filename.isBlank()) {
            return null;
        }
        JsonNode arr = issueFields.get("attachment");
        if (arr == null || !arr.isArray()) {
            return null;
        }
        for (JsonNode a : arr) {
            if (filename.equals(text(a, "filename"))) {
                String contentUrl = text(a, "content");
                return contentUrl != null ? new AttachmentRef(contentUrl, text(a, "mimeType")) : null;
            }
        }
        return null;
    }

    /** 附件引用：contentUrl=Jira 返回的内容绝对地址（/secure/attachment/{id}/{name}），mimeType 可空 */
    record AttachmentRef(String contentUrl, String mimeType) {
    }

    /**
     * CAP-47：POST /issue 响应 → IssueRef。缺 key 视为脏数据（本次创建拿不到外部键，
     * 后续无法登记 external_links）返回 null，由调用方报错，绝不落半吊子 link。
     */
    static IssueRef toIssueRef(JsonNode body, String baseUrl) {
        String key = text(body, "key");
        if (key == null) {
            return null;
        }
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return new IssueRef(text(body, "id"), key, base + "/browse/" + key);
    }

    /**
     * CAP-47 FR-02：任务类型清单。兼容两种响应——
     * 新端点 {@code /issue/createmeta/{key}/issuetypes} 的 {@code {values:[...]}}，
     * 与旧端点 {@code /issue/createmeta?projectKeys=} 的 {@code {projects:[{issuetypes:[...]}]}}。
     * 缺 id 的脏条目跳过（无法回传创建）；subtask 原样透出，由服务层过滤。
     */
    static List<IssueTypeRef> toIssueTypes(JsonNode body) {
        JsonNode arr = body == null ? null : body.get("values");
        if (arr == null || !arr.isArray()) {
            JsonNode projects = body == null ? null : body.get("projects");
            arr = (projects != null && projects.isArray() && !projects.isEmpty())
                    ? projects.get(0).get("issuetypes") : null;
        }
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<IssueTypeRef> out = new ArrayList<>();
        for (JsonNode t : arr) {
            String id = text(t, "id");
            if (id != null) {
                out.add(new IssueTypeRef(id, text(t, "name"), t.path("subtask").asBoolean(false)));
            }
        }
        return out;
    }

    /**
     * CAP-47 FR-08：创建字段元数据。兼容两种响应——
     * 新端点 {@code /issue/createmeta/{key}/issuetypes/{id}} 的
     * {@code {values:[{fieldId,name,required,hasDefaultValue,schema,allowedValues}]}}，
     * 与旧端点 {@code /issue/createmeta?...&expand=projects.issuetypes.fields} 的
     * {@code {projects:[{issuetypes:[{fields:{<fieldId>:{…}}}]}]}}。
     * 缺 fieldId 的脏条目跳过（拿不到键就无法回传取值）。
     */
    static List<CreateFieldRef> toCreateFields(JsonNode body) {
        List<JsonNode> raws = new ArrayList<>();
        JsonNode values = body == null ? null : body.get("values");
        if (values != null && values.isArray()) {
            for (JsonNode f : values) {
                raws.add(f);
            }
        } else {
            // 旧版：fields 是以 fieldId 为键的对象，键本身才是 id（对象内不再重复 fieldId），补进去后走同一段解析
            JsonNode fields = legacyFields(body);
            if (fields != null && fields.isObject()) {
                var it = fields.properties().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    JsonNode v = entry.getValue();
                    if (v == null) {
                        continue;
                    }
                    if (v.isObject() && text(v, "fieldId") == null) {
                        ((ObjectNode) v).put("fieldId", entry.getKey());
                    }
                    raws.add(v);
                }
            }
        }
        List<CreateFieldRef> out = new ArrayList<>();
        for (JsonNode f : raws) {
            String id = text(f, "fieldId");
            if (id == null) {
                continue;
            }
            JsonNode schema = f.get("schema");
            out.add(new CreateFieldRef(id, text(f, "name"), f.path("required").asBoolean(false),
                    schema == null ? null : text(schema, "type"),
                    schema == null ? null : text(schema, "items"),
                    toFieldOptions(f.get("allowedValues")),
                    f.path("hasDefaultValue").asBoolean(false)));
        }
        return out;
    }

    /** 旧版 createmeta：projects[0].issuetypes[0].fields */
    private static JsonNode legacyFields(JsonNode body) {
        JsonNode projects = body == null ? null : body.get("projects");
        if (projects == null || !projects.isArray() || projects.isEmpty()) {
            return null;
        }
        JsonNode types = projects.get(0).get("issuetypes");
        if (types == null || !types.isArray() || types.isEmpty()) {
            return null;
        }
        return types.get(0).get("fields");
    }

    /**
     * allowedValues → 选项表。展示名取 value 再退 name：option 类字段是
     * {@code {id,value}}，组件/版本类字段是 {@code {id,name}}。
     * 无 id 的条目仍可用 name 回传（Jira 两种都收），故退化为 id=name。
     */
    private static List<FieldOption> toFieldOptions(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<FieldOption> out = new ArrayList<>();
        for (JsonNode o : arr) {
            String name = text(o, "value") != null ? text(o, "value") : text(o, "name");
            if (name == null) {
                continue;
            }
            String id = text(o, "id");
            out.add(new FieldOption(id != null ? id : name, name));
        }
        return out;
    }

    /**
     * CAP-47 FR-02：优先级词表 [{id,name}]；实例关闭优先级功能时返回空表。
     * 按 name 过滤脏条目（创建 issue 回传的是 priority.name，id 非必需，缺 id 不影响可用）。
     */
    static List<PriorityRef> toPriorities(JsonNode body) {
        if (body == null || !body.isArray()) {
            return List.of();
        }
        List<PriorityRef> out = new ArrayList<>();
        for (JsonNode p : body) {
            String name = text(p, "name");
            if (name != null) {
                out.add(new PriorityRef(text(p, "id"), name));
            }
        }
        return out;
    }

    /**
     * CAP-47 FR-02：可指派用户 [{name,displayName}]。name 为平台用户名（回传给 Jira 创建 issue 用），
     * displayName 缺失时退 name（部分实例无显示名）。
     */
    static List<UserRef> toAssignableUsers(JsonNode body) {
        if (body == null || !body.isArray()) {
            return List.of();
        }
        List<UserRef> out = new ArrayList<>();
        for (JsonNode u : body) {
            String name = text(u, "name");
            if (name != null) {
                String display = text(u, "displayName");
                out.add(new UserRef(name, display != null ? display : name));
            }
        }
        return out;
    }

    /** time tracking 字段（秒）：数值取 long，null/非数值（实例未启用工时跟踪）返回 null */
    private static Long seconds(JsonNode fields, String field) {
        JsonNode v = fields == null ? null : fields.get(field);
        return v != null && v.isNumber() ? v.asLong() : null;
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
}
