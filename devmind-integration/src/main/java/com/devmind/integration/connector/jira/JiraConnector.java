package com.devmind.integration.connector.jira;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.config.IntegrationProperties;
import com.devmind.integration.connector.IntegrationConnector;
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
 * 读：拉取 issue / 工作流转换清单 / 附件内容（CAP-19 FR-09 描述图片代理）；
 * 写：仅限 transitions / worklog 端点（CAP-19 FR-08 状态回写、
 * CAP-27 工时登记），git 动词不支持。
 * 与 GitLabConnector 同一手法：查询参数自行 URL 编码后拼完整 URI，
 * 避开 RestClient URI 模板展开的二次编码。
 */
@Component
public class JiraConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);

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
            JsonNode body = mapper.readTree(e.getResponseBodyAsString());
            JsonNode msgs = body.get("errorMessages");
            if (msgs != null && msgs.isArray() && !msgs.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (JsonNode m : msgs) {
                    out.add(m.asText());
                }
                return String.join("；", out);
            }
            JsonNode errors = body.get("errors");
            if (errors != null && errors.isObject()) {
                return errors.toString();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体
        }
        String raw = e.getResponseBodyAsString();
        return raw == null ? "" : (raw.length() <= 300 ? raw : raw.substring(0, 300));
    }
}
