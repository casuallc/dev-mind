package com.devmind.integration.connector.gitlab;

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
import java.util.Map;

/**
 * CAP-18 GitLab 连接器（v4 API，PAT 走 PRIVATE-TOKEN 头）。
 * MR 重复创建（409）与 Release 已存在（409）均降级为返回既有对象（幂等）。
 * 注意：项目 key（group/path）自行 URL 编码后以完整 URI 发起请求——
 * 走 RestClient URI 模板展开会把 %2F 二次编码成 %252F。
 */
@Component
public class GitLabConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(GitLabConnector.class);

    private final IntegrationProperties props;
    private final ObjectMapper mapper;

    public GitLabConnector(IntegrationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return IntegrationEntity.TYPE_GITLAB;
    }

    @Override
    public TestResult testConnection(IntegrationEntity cfg, String token) {
        try {
            JsonNode user = client(token).get().uri(uri(cfg, "/user")).retrieve().body(JsonNode.class);
            String username = user != null && user.has("username") ? user.get("username").asText() : "?";
            String version;
            try {
                JsonNode v = client(token).get().uri(uri(cfg, "/version")).retrieve().body(JsonNode.class);
                version = v != null && v.has("version") ? v.get("version").asText() : "未知";
            } catch (Exception e) {
                version = "不可读（scope 可能缺 api）";
            }
            return new TestResult(true, "连接成功：用户 " + username, "GitLab " + version + " · " + cfg.getBaseUrl());
        } catch (RestClientResponseException e) {
            return new TestResult(false, "连接失败：HTTP " + e.getStatusCode().value(), extractMessage(e));
        } catch (Exception e) {
            return new TestResult(false, "连接失败：" + e.getMessage(), cfg.getBaseUrl());
        }
    }

    @Override
    public List<ExternalProject> listProjects(IntegrationEntity cfg, String token) {
        try {
            JsonNode arr = client(token).get()
                    .uri(uri(cfg, "/projects?membership=true&simple=true&per_page=100&order_by=last_activity_at"))
                    .retrieve().body(JsonNode.class);
            List<ExternalProject> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode p : arr) {
                    out.add(new ExternalProject(
                            p.has("id") ? p.get("id").asText() : null,
                            p.has("path_with_namespace") ? p.get("path_with_namespace").asText() : null,
                            p.has("web_url") ? p.get("web_url").asText() : null,
                            p.has("default_branch") && !p.get("default_branch").isNull()
                                    ? p.get("default_branch").asText() : null));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "列出 GitLab 项目失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public MergeRequestRef createMergeRequest(IntegrationEntity cfg, String token, MrSpec spec) {
        RestClient c = client(token);
        String project = encodeProjectKey(spec.projectKey());
        try {
            JsonNode mr = c.post().uri(uri(cfg, "/projects/" + project + "/merge_requests"))
                    .body(Map.of(
                            "source_branch", spec.sourceBranch(),
                            "target_branch", spec.targetBranch(),
                            "title", spec.title(),
                            "description", spec.description() == null ? "" : spec.description()))
                    .retrieve().body(JsonNode.class);
            return new MergeRequestRef(mr.get("iid").asText(),
                    mr.has("web_url") ? mr.get("web_url").asText() : null,
                    mr.has("state") ? mr.get("state").asText() : null, false);
        } catch (RestClientResponseException e) {
            // 409 = 同源分支已有未关闭 MR → 查既有返回（幂等）
            if (e.getStatusCode().value() == 409) {
                MergeRequestRef existing = findOpenMr(cfg, c, project, spec.sourceBranch());
                if (existing != null) {
                    return existing;
                }
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "创建 MR 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** 查同源分支的未关闭 MR；找不到返回 null */
    private MergeRequestRef findOpenMr(IntegrationEntity cfg, RestClient c, String project, String sourceBranch) {
        try {
            JsonNode arr = c.get()
                    .uri(uri(cfg, "/projects/" + project + "/merge_requests?state=opened&source_branch="
                            + URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8)))
                    .retrieve().body(JsonNode.class);
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                JsonNode mr = arr.get(0);
                return new MergeRequestRef(mr.get("iid").asText(),
                        mr.has("web_url") ? mr.get("web_url").asText() : null,
                        mr.has("state") ? mr.get("state").asText() : null, true);
            }
        } catch (Exception e) {
            log.warn("查询既有 MR 失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public ReleaseRef createRelease(IntegrationEntity cfg, String token, ReleaseSpec spec) {
        RestClient c = client(token);
        String project = encodeProjectKey(spec.projectKey());
        try {
            JsonNode rel = c.post().uri(uri(cfg, "/projects/" + project + "/releases"))
                    .body(Map.of(
                            "tag_name", spec.tagName(),
                            "name", spec.name(),
                            "description", spec.description() == null ? "" : spec.description()))
                    .retrieve().body(JsonNode.class);
            return new ReleaseRef(spec.tagName(), releaseUrl(cfg, rel, spec), false);
        } catch (RestClientResponseException e) {
            // 409 = 该 tag 的 Release 已存在 → 返回既有（幂等）
            if (e.getStatusCode().value() == 409) {
                try {
                    JsonNode rel = c.get()
                            .uri(uri(cfg, "/projects/" + project + "/releases/"
                                    + URLEncoder.encode(spec.tagName(), StandardCharsets.UTF_8)))
                            .retrieve().body(JsonNode.class);
                    return new ReleaseRef(spec.tagName(), releaseUrl(cfg, rel, spec), true);
                } catch (Exception ex) {
                    log.warn("查询既有 Release 失败: {}", ex.getMessage());
                }
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "创建 Release 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** Release 响应无 web_url 字段，拼平台页面地址 */
    private String releaseUrl(IntegrationEntity cfg, JsonNode rel, ReleaseSpec spec) {
        if (rel != null && rel.has("_links") && rel.get("_links").has("self")) {
            return rel.get("_links").get("self").asText();
        }
        return cfg.getBaseUrl().replaceAll("/+$", "") + "/" + spec.projectKey()
                + "/-/releases/" + spec.tagName();
    }

    /** GitLab 项目键：纯数字为 id 直接用；path 需 URL 编码（group/sub/proj → group%2Fsub%2Fproj） */
    private String encodeProjectKey(String key) {
        if (key != null && key.matches("\\d+")) {
            return key;
        }
        return URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    /** 完整 API URI（base_url + /api/v4 + 已编码的 path）——避开模板展开二次编码 */
    private URI uri(IntegrationEntity cfg, String encodedPathAndQuery) {
        return URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/api/v4" + encodedPathAndQuery);
    }

    private RestClient client(String token) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("PRIVATE-TOKEN", token)
                .build();
    }

    /** 从 GitLab 错误响应提取 message（{"message":...} 或 {"error":...}），不含任何凭据 */
    private String extractMessage(RestClientResponseException e) {
        try {
            JsonNode body = mapper.readTree(e.getResponseBodyAsString());
            if (body.has("message")) {
                JsonNode m = body.get("message");
                return m.isTextual() ? m.asText() : m.toString();
            }
            if (body.has("error")) {
                return body.get("error").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体
        }
        String raw = e.getResponseBodyAsString();
        return raw == null ? "" : (raw.length() <= 300 ? raw : raw.substring(0, 300));
    }
}
