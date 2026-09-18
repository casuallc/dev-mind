package com.devmind.integration.connector.jira;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.config.IntegrationProperties;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.connector.IntegrationConnector.CreateFieldRef;
import com.devmind.integration.connector.IntegrationConnector.IssueRef;
import com.devmind.integration.connector.IntegrationConnector.IssueSpec;
import com.devmind.integration.connector.IntegrationConnector.IssueTypeRef;
import com.devmind.integration.connector.IntegrationConnector.PriorityRef;
import com.devmind.integration.connector.IntegrationConnector.UserRef;
import com.devmind.integration.model.IntegrationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Jira Server/DC 连接器（/rest/api/2）。认证按凭据格式自探测：
 * PAT（8.14+，Bearer 头）/ BASIC（8.13 及更早，Basic base64(user:password)，
 * secret 含换行即 BASIC），与个人账号（CAP-35）/实例机器人凭据无关来源均适用。
 * 读：拉取 issue / 单条读取 / 工作流转换清单 / 附件内容（CAP-19 FR-09 描述图片代理）/
 * 任务类型·优先级·可指派用户（CAP-47 FR-02）/ 创建字段元数据（CAP-47 FR-08）；
 * 写：仅限 createIssue / transitions / worklog 端点（CAP-47 FR-01 创建 issue、
 * CAP-19 FR-08 状态回写、CAP-27 工时登记），git 动词不支持。
 * 与 GitLabConnector 同一手法：查询参数自行 URL 编码后拼完整 URI，
 * 避开 RestClient URI 模板展开的二次编码。
 */
@Component
public class JiraConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);

    /** CAP-47 FR-02：任务类型翻页大小与页数上限（类型数远超实际，防某实例异常分页导致死循环） */
    private static final int ISSUE_TYPE_PAGE_SIZE = 50;
    private static final int MAX_ISSUE_TYPE_PAGES = 5;

    /**
     * CAP-47 FR-02：可指派用户单次返回上限。取 20 时用户抱怨「找不到人」——空关键字直接列默认
     * 列表的用法下，一个稍大的项目里目标用户排在 20 名开外就永远看不到；搜关键字虽由 Jira 过滤，
     * 但它匹配的是用户名/显示名/邮箱，显示名与登录名对不上时同样搜不到。放宽到 200（Jira 侧
     * 只认这个参数上界，不回 total，故不做翻页）。
     */
    private static final int ASSIGNABLE_USER_LIMIT = 200;

    public JiraConnector(IntegrationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    private final IntegrationProperties props;
    private final ObjectMapper mapper;

    @Override
    public String type() {
        return IntegrationEntity.TYPE_JIRA;
    }

    @Override
    public TestResult testConnection(IntegrationEntity cfg, String token) {
        try {
            JsonNode me = client(cfg, token).get().uri(uri(cfg, "/myself")).retrieve().body(JsonNode.class);
            String who = me != null && me.has("displayName") ? me.get("displayName").asText()
                    : (me != null && me.has("name") ? me.get("name").asText() : "?");
            String version;
            try {
                JsonNode info = client(cfg, token).get().uri(uri(cfg, "/serverInfo")).retrieve().body(JsonNode.class);
                version = info != null && info.has("version") ? info.get("version").asText() : "未知";
            } catch (Exception e) {
                version = "不可读（serverInfo 权限不足）";
            }
            return new TestResult(true, "连接成功：用户 " + who, "Jira " + version + " · " + cfg.getBaseUrl());
        } catch (RestClientResponseException e) {
            return new TestResult(false, "连接失败：HTTP " + e.getStatusCode().value(), extractMessage(e));
        } catch (Exception e) {
            return new TestResult(false, "连接失败：" + e.getMessage(), cfg.getBaseUrl());
        }
    }

    @Override
    public List<ExternalProject> listProjects(IntegrationEntity cfg, String token) {
        try {
            JsonNode arr = client(cfg, token).get().uri(uri(cfg, "/project")).retrieve().body(JsonNode.class);
            List<ExternalProject> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                String base = cfg.getBaseUrl().replaceAll("/+$", "");
                for (JsonNode p : arr) {
                    String key = p.has("key") ? p.get("key").asText() : null;
                    out.add(new ExternalProject(key,
                            p.has("name") ? p.get("name").asText() : null,
                            key != null ? base + "/browse/" + key : null,
                            null));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "列出 Jira 项目失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public IssuePage searchIssues(IntegrationEntity cfg, String token, IssueQuery query) {
        StringBuilder path = new StringBuilder("/search?jql=")
                .append(URLEncoder.encode(query.jql(), StandardCharsets.UTF_8))
                .append("&startAt=").append(query.startAt())
                .append("&maxResults=").append(query.maxResults());
        if (query.fields() != null && !query.fields().isBlank()) {
            path.append("&fields=").append(URLEncoder.encode(query.fields(), StandardCharsets.UTF_8));
        }
        try {
            JsonNode body = client(cfg, token).get().uri(uri(cfg, path.toString())).retrieve().body(JsonNode.class);
            return JiraIssueMapper.toPage(body);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira issue 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public List<IssueTransition> listTransitions(IntegrationEntity cfg, String token, String issueKey) {
        try {
            JsonNode body = client(cfg, token).get()
                    .uri(uri(cfg, "/issue/" + encodeKey(issueKey) + "/transitions"))
                    .retrieve().body(JsonNode.class);
            return JiraIssueMapper.toTransitions(body);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 转换清单失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public void transitionIssue(IntegrationEntity cfg, String token, String issueKey, String transitionId) {
        var payload = mapper.createObjectNode();
        payload.putObject("transition").put("id", transitionId);
        try {
            client(cfg, token).post()
                    .uri(uri(cfg, "/issue/" + encodeKey(issueKey) + "/transitions"))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "Jira 状态转换失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public void logWork(IntegrationEntity cfg, String token, String issueKey, long seconds, String comment) {
        var payload = mapper.createObjectNode();
        payload.put("timeSpentSeconds", seconds);
        if (comment != null && !comment.isBlank()) {
            payload.put("comment", comment.trim());
        }
        try {
            client(cfg, token).post()
                    .uri(uri(cfg, "/issue/" + encodeKey(issueKey) + "/worklog"))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "Jira 工时登记失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /**
     * CAP-47 FR-01：创建 issue。空值字段一律不写进 payload——写 null 会显式清空 Jira 侧
     * 默认值（如项目默认经办人）。duedate 用字符串手工拼（不给 ObjectMapper 加 JavaTimeModule）。
     *
     * <p>动态字段（{@link IssueSpec#extraFields}）**先写**、固定字段后写：正常路径两者交集为空
     * （服务层已拒绝覆盖固定字段 id），真撞上时以平台语义确定的固定字段为准。
     */
    @Override
    public IssueRef createIssue(IntegrationEntity cfg, String token, IssueSpec spec) {
        var fields = mapper.createObjectNode();
        if (spec.extraFields() != null) {
            for (var entry : spec.extraFields().entrySet()) {
                fields.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
            }
        }
        fields.putObject("project").put("key", spec.projectKey());
        fields.putObject("issuetype").put("id", spec.issueTypeId());
        fields.put("summary", spec.summary());
        if (notBlank(spec.description())) {
            fields.put("description", spec.description());
        }
        if (notBlank(spec.priorityName())) {
            fields.putObject("priority").put("name", spec.priorityName());
        }
        if (notBlank(spec.assigneeName())) {
            fields.putObject("assignee").put("name", spec.assigneeName());
        }
        if (spec.labels() != null && !spec.labels().isEmpty()) {
            var labels = fields.putArray("labels");
            for (String label : spec.labels()) {
                if (notBlank(label)) {
                    labels.add(label.trim());
                }
            }
        }
        if (spec.dueDate() != null) {
            fields.put("duedate", spec.dueDate().toString());
        }
        var payload = mapper.createObjectNode();
        payload.set("fields", fields);
        try {
            JsonNode body = client(cfg, token).post().uri(uri(cfg, "/issue"))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().body(JsonNode.class);
            IssueRef ref = JiraIssueMapper.toIssueRef(body, cfg.getBaseUrl());
            if (ref == null) {
                throw new DevMindException(ErrorCode.INTERNAL, "Jira 创建 issue 响应缺少 key");
            }
            return ref;
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "创建 Jira issue 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** CAP-47 FR-01：单条读取（不经过搜索索引，新建 issue 后索引有延迟） */
    @Override
    public JiraIssue getIssue(IntegrationEntity cfg, String token, String issueKey, String fields) {
        String path = "/issue/" + encodeKey(issueKey);
        if (notBlank(fields)) {
            path += "?fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);
        }
        try {
            JsonNode body = client(cfg, token).get().uri(uri(cfg, path)).retrieve().body(JsonNode.class);
            JiraIssue issue = JiraIssueMapper.toIssue(body);
            if (issue == null) {
                throw new DevMindException(ErrorCode.NOT_FOUND, "Jira issue 不存在或不可读: " + issueKey);
            }
            return issue;
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "读取 Jira issue 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /**
     * CAP-47 FR-02：项目下当前账号可创建的 issue 类型。主路径为 8.4+ 新端点
     * {@code /issue/createmeta/{key}/issuetypes}（需按 total/isLast 翻页）；
     * 老实例（<8.4）新端点 404，兜底旧端点 {@code /issue/createmeta?projectKeys=&expand=projects.issuetypes}。
     */
    @Override
    public List<IssueTypeRef> listIssueTypes(IntegrationEntity cfg, String token, String projectKey) {
        try {
            List<IssueTypeRef> out = new ArrayList<>();
            int startAt = 0;
            for (int page = 0; page < MAX_ISSUE_TYPE_PAGES; page++) {
                JsonNode body = client(cfg, token).get()
                        .uri(uri(cfg, "/issue/createmeta/" + encodeKey(projectKey) + "/issuetypes?startAt="
                                + startAt + "&maxResults=" + ISSUE_TYPE_PAGE_SIZE))
                        .retrieve().body(JsonNode.class);
                List<IssueTypeRef> batch = JiraIssueMapper.toIssueTypes(body);
                out.addAll(batch);
                if (batch.isEmpty() || body.path("isLast").asBoolean(batch.size() < ISSUE_TYPE_PAGE_SIZE)) {
                    break;
                }
                startAt += ISSUE_TYPE_PAGE_SIZE;
            }
            return out;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return listIssueTypesLegacy(cfg, token, projectKey);
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 任务类型失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** 旧版 createmeta（Jira 8.4 前；新端点 404 时兜底） */
    private List<IssueTypeRef> listIssueTypesLegacy(IntegrationEntity cfg, String token, String projectKey) {
        try {
            JsonNode body = client(cfg, token).get()
                    .uri(uri(cfg, "/issue/createmeta?projectKeys=" + encodeKey(projectKey)
                            + "&expand=" + URLEncoder.encode("projects.issuetypes", StandardCharsets.UTF_8)))
                    .retrieve().body(JsonNode.class);
            return JiraIssueMapper.toIssueTypes(body);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 任务类型失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** CAP-47 FR-02：优先级词表（实例关闭优先级功能时返回空表，前端隐藏该项） */
    @Override
    public List<PriorityRef> listPriorities(IntegrationEntity cfg, String token) {
        try {
            JsonNode body = client(cfg, token).get().uri(uri(cfg, "/priority")).retrieve().body(JsonNode.class);
            return JiraIssueMapper.toPriorities(body);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 优先级失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /**
     * CAP-47 FR-08：创建字段元数据。主路径 {@code /issue/createmeta/{key}/issuetypes/{id}}
     * （8.4+，需按 total/isLast 翻页）；老实例 404 时兜底旧端点
     * {@code /issue/createmeta?projectKeys=&issuetypeIds=&expand=projects.issuetypes.fields}。
     * 两条路径的响应形态不同，由 {@link JiraIssueMapper#toCreateFields} 归一。
     */
    @Override
    public List<CreateFieldRef> listCreateFields(IntegrationEntity cfg, String token,
                                                 String projectKey, String issueTypeId) {
        try {
            List<CreateFieldRef> out = new ArrayList<>();
            int startAt = 0;
            for (int page = 0; page < MAX_ISSUE_TYPE_PAGES; page++) {
                JsonNode body = client(cfg, token).get()
                        .uri(uri(cfg, "/issue/createmeta/" + encodeKey(projectKey) + "/issuetypes/"
                                + encodeKey(issueTypeId) + "?startAt=" + startAt
                                + "&maxResults=" + ISSUE_TYPE_PAGE_SIZE))
                        .retrieve().body(JsonNode.class);
                List<CreateFieldRef> batch = JiraIssueMapper.toCreateFields(body);
                out.addAll(batch);
                if (batch.isEmpty() || body.path("isLast").asBoolean(batch.size() < ISSUE_TYPE_PAGE_SIZE)) {
                    break;
                }
                startAt += ISSUE_TYPE_PAGE_SIZE;
            }
            return out;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return listCreateFieldsLegacy(cfg, token, projectKey, issueTypeId);
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 创建字段失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** 旧版 createmeta 字段（Jira 8.4 前；新端点 404 时兜底）：fields 为 fieldId 为键的对象 */
    private List<CreateFieldRef> listCreateFieldsLegacy(IntegrationEntity cfg, String token,
                                                       String projectKey, String issueTypeId) {
        try {
            JsonNode body = client(cfg, token).get()
                    .uri(uri(cfg, "/issue/createmeta?projectKeys=" + encodeKey(projectKey)
                            + "&issuetypeIds=" + encodeKey(issueTypeId)
                            + "&expand=" + URLEncoder.encode("projects.issuetypes.fields", StandardCharsets.UTF_8)))
                    .retrieve().body(JsonNode.class);
            return JiraIssueMapper.toCreateFields(body);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 创建字段失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /**
     * CAP-47 FR-02：项目可指派用户。query 参数在 GDPR 严格模式的实例上被拒（400），
     * 此时退 username 重试一次；两者都失败由调用方降级为纯文本输入，不阻断推送。
     *
     * <p>{@code q} 不保证被服务端采纳：老 Server（GDPR 前）只认 {@code username}，收到
     * 不认识的 {@code query} 既不报错也不过滤——实测搜一个不存在的人仍原样返回全部 102 个。
     * 所以前端不靠这个参数做候选收敛，改为对取全的清单本地过滤（见 JiraPushModal）；
     * 这里的 {@code q} 只当作「新实例能省一次全量」的优化。
     */
    @Override
    public List<UserRef> listAssignableUsers(IntegrationEntity cfg, String token, String projectKey, String q) {
        try {
            return fetchAssignableUsers(cfg, token, projectKey, q, "query");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 && extractMessage(e).contains("GDPR")) {
                try {
                    return fetchAssignableUsers(cfg, token, projectKey, q, "username");
                } catch (RestClientResponseException e2) {
                    throw assignableUsersError(e2);
                }
            }
            throw assignableUsersError(e);
        }
    }

    private List<UserRef> fetchAssignableUsers(IntegrationEntity cfg, String token, String projectKey,
                                              String q, String queryParam) {
        StringBuilder path = new StringBuilder("/user/assignable/search?project=")
                .append(encodeKey(projectKey))
                .append("&maxResults=").append(ASSIGNABLE_USER_LIMIT);
        if (notBlank(q)) {
            path.append('&').append(queryParam).append('=')
                    .append(URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
        }
        JsonNode body = client(cfg, token).get().uri(uri(cfg, path.toString())).retrieve().body(JsonNode.class);
        return JiraIssueMapper.toAssignableUsers(body);
    }

    private DevMindException assignableUsersError(RestClientResponseException e) {
        return new DevMindException(ErrorCode.BAD_REQUEST,
                "拉取 Jira 可指派用户失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * CAP-19 FR-09：issue 附件内容拉取（描述 wiki 图片标记 !name.png! 的按需代理数据源）。
     * 两步：先取 issue 的 attachment 元数据按文件名定位内容直链，再以同一凭据拉字节流。
     */
    @Override
    public IssueAttachment fetchIssueAttachment(IntegrationEntity cfg, String token, String issueKey,
                                                String filename) {
        JiraIssueMapper.AttachmentRef ref;
        try {
            JsonNode body = client(cfg, token).get()
                    .uri(uri(cfg, "/issue/" + encodeKey(issueKey) + "?fields=attachment"))
                    .retrieve().body(JsonNode.class);
            ref = JiraIssueMapper.toAttachmentRef(body == null ? null : body.get("fields"), filename);
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 附件清单失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
        if (ref == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND,
                    "Jira issue " + issueKey + " 无附件: " + filename);
        }
        try {
            byte[] bytes = client(cfg, token).get().uri(URI.create(ref.contentUrl()))
                    .retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new DevMindException(ErrorCode.NOT_FOUND,
                        "Jira 附件内容为空: " + issueKey + " " + filename);
            }
            return new IssueAttachment(bytes, ref.mimeType());
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "拉取 Jira 附件内容失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** issue key 进路径段（PROJ-123 这类本安全，编码兜底防脏数据） */
    private static String encodeKey(String issueKey) {
        return URLEncoder.encode(issueKey, StandardCharsets.UTF_8);
    }

    @Override
    public MergeRequestRef createMergeRequest(IntegrationEntity cfg, String token, MrSpec spec) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, "Jira 集成无 git 能力");
    }

    @Override
    public ReleaseRef createRelease(IntegrationEntity cfg, String token, ReleaseSpec spec) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, "Jira 集成无 git 能力");
    }

    /** 完整 API URI（base_url + /rest/api/2 + 已编码的 path） */
    private URI uri(IntegrationEntity cfg, String encodedPathAndQuery) {
        return URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/rest/api/2" + encodedPathAndQuery);
    }

    private RestClient client(IntegrationEntity cfg, String token) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", authorizationHeader(token))
                .build();
    }

    /**
     * Authorization 头组装：按 secret 存储格式自探测（不按实例 authType）——
     * 含换行即 BASIC 的 "username\npassword"（见 IntegrationService.encodeSecret）
     * 转 Basic base64(username:password)，否则按 PAT 走 Bearer。
     * 原因：CAP-35 个人账号的认证方式独立于实例（resolveWriteIdentity 只回 secret），
     * 凭据格式才是权威来源；实例 BASIC + 个人 PAT（或反之）时必须按实际凭据组头。
     */
    static String authorizationHeader(String secret) {
        int i = secret == null ? -1 : secret.indexOf('\n');
        if (i >= 0) {
            String raw = secret.substring(0, i) + ":" + secret.substring(i + 1);
            return "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + secret;
    }

    /** Jira 错误响应：{"errorMessages":[...],"errors":{...}}，不含任何凭据 */
    private String extractMessage(RestClientResponseException e) {
        try {
            String described = describeError(mapper.readTree(e.getResponseBodyAsString()));
            if (described != null) {
                return described;
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体
        }
        String raw = e.getResponseBodyAsString();
        return raw == null ? "" : (raw.length() <= 300 ? raw : raw.substring(0, 300));
    }

    /**
     * Jira 错误体 → 单行可读文案；不是 Jira 错误封套时返回 null（交调用方退原始报文）。
     *
     * <p>errorMessages 与 errors **都要**：创建 issue 时 Jira 常同时给
     * （如「工作流校验失败」+ 逐字段的必填/取值明细），只取前者会把 8 条必填明细丢掉。
     * errors 的 value 已本地化（「模块是必需的。」），key 是字段 id——逐条
     * {@code key: value} 展开；直接 {@code toString()} 出来的 JSON 挤成一行，
     * 用户既读不出哪几个字段必填，也看不到「用户 '刘长青' 不存在」这类取值错。
     */
    static String describeError(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        JsonNode msgs = body.get("errorMessages");
        if (msgs != null && msgs.isArray()) {
            for (JsonNode m : msgs) {
                parts.add(m.asText());
            }
        }
        JsonNode errors = body.get("errors");
        if (errors != null && errors.isObject()) {
            var it = errors.properties().iterator();
            while (it.hasNext()) {
                var field = it.next();
                parts.add(field.getKey() + ": " + field.getValue().asText());
            }
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }
}
