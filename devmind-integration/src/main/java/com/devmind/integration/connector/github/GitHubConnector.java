package com.devmind.integration.connector.github;

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
 * CAP-22 GitHub 连接器（REST v3 API，PAT 走 Authorization: Bearer 头）。
 * API 基址分流：github.com → https://api.github.com；GHE → &lt;base_url&gt;/api/v3。
 * PR 已存在（422）与 Release 已存在（422）均降级为返回既有对象（幂等）。
 * 项目 key 固定为 owner/repo（full_name），路径参数逐段编码、不编码 "/"——
 * 与 GitLab 的 %2F 整段编码不同，自行拼完整 URI 避开模板展开二次编码。
 */
@Component
public class GitHubConnector implements IntegrationConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);

    private final IntegrationProperties props;
    private final ObjectMapper mapper;

    public GitHubConnector(IntegrationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return IntegrationEntity.TYPE_GITHUB;
    }

    @Override
    public TestResult testConnection(IntegrationEntity cfg, String token) {
        try {
            var resp = client(token).get().uri(uri(cfg, "/user")).retrieve().toEntity(JsonNode.class);
            JsonNode user = resp.getBody();
            String login = user != null && user.has("login") ? user.get("login").asText() : "?";
            String scopes = resp.getHeaders().getFirst("X-OAuth-Scopes");
            String scopeInfo = scopes != null && !scopes.isBlank()
                    ? "scopes: " + scopes
                    : "fine-grained PAT（无 scope 头，按仓库授权）";
            return new TestResult(true, "连接成功：用户 " + login,
                    scopeInfo + " · " + apiBase(cfg.getBaseUrl()));
        } catch (RestClientResponseException e) {
            return new TestResult(false, "连接失败：HTTP " + e.getStatusCode().value(), extractMessage(e));
        } catch (Exception e) {
            return new TestResult(false, "连接失败：" + e.getMessage(), apiBase(cfg.getBaseUrl()));
        }
    }

    @Override
    public List<ExternalProject> listProjects(IntegrationEntity cfg, String token) {
        try {
            JsonNode arr = client(token).get()
                    .uri(uri(cfg, "/user/repos?affiliation=owner,collaborator,organization_member"
                            + "&sort=updated&per_page=100"))
                    .retrieve().body(JsonNode.class);
            List<ExternalProject> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode p : arr) {
                    out.add(new ExternalProject(
                            p.has("full_name") ? p.get("full_name").asText() : null,
                            p.has("full_name") ? p.get("full_name").asText() : null,
                            p.has("html_url") ? p.get("html_url").asText() : null,
                            p.has("default_branch") && !p.get("default_branch").isNull()
                                    ? p.get("default_branch").asText() : null));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "列出 GitHub 仓库失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    @Override
    public MergeRequestRef createMergeRequest(IntegrationEntity cfg, String token, MrSpec spec) {
        RestClient c = client(token);
        String repo = encodeRepoPath(spec.projectKey());
        try {
            JsonNode pr = c.post().uri(uri(cfg, "/repos/" + repo + "/pulls"))
                    .body(Map.of(
                            "head", spec.sourceBranch(),
                            "base", spec.targetBranch(),
                            "title", spec.title(),
                            "body", spec.description() == null ? "" : spec.description()))
                    .retrieve().body(JsonNode.class);
            return prRef(pr, false);
        } catch (RestClientResponseException e) {
            // 422 = 同源分支已有未关闭 PR → 查既有返回（幂等）
            if (e.getStatusCode().value() == 422) {
                MergeRequestRef existing = findOpenPr(cfg, c, spec.projectKey(), spec.sourceBranch());
                if (existing != null) {
                    return existing;
                }
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "创建 PR 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /** 查同源分支的未关闭 PR（head=owner:branch）；找不到返回 null */
    private MergeRequestRef findOpenPr(IntegrationEntity cfg, RestClient c, String projectKey, String sourceBranch) {
        String owner = projectKey != null && projectKey.contains("/")
                ? projectKey.substring(0, projectKey.indexOf('/')) : projectKey;
        try {
            JsonNode arr = c.get()
                    .uri(uri(cfg, "/repos/" + encodeRepoPath(projectKey) + "/pulls?state=open&head="
                            + URLEncoder.encode(owner + ":" + sourceBranch, StandardCharsets.UTF_8)))
                    .retrieve().body(JsonNode.class);
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                return prRef(arr.get(0), true);
            }
        } catch (Exception e) {
            log.warn("查询既有 PR 失败: {}", e.getMessage());
        }
        return null;
    }

    private MergeRequestRef prRef(JsonNode pr, boolean reused) {
        return new MergeRequestRef(pr.get("number").asText(),
                pr.has("html_url") ? pr.get("html_url").asText() : null,
                pr.has("state") ? pr.get("state").asText() : null, reused);
    }

    @Override
    public ReleaseRef createRelease(IntegrationEntity cfg, String token, ReleaseSpec spec) {
        RestClient c = client(token);
        String repo = encodeRepoPath(spec.projectKey());
        try {
            JsonNode rel = c.post().uri(uri(cfg, "/repos/" + repo + "/releases"))
                    .body(Map.of(
                            "tag_name", spec.tagName(),
                            "name", spec.name(),
                            "body", spec.description() == null ? "" : spec.description()))
                    .retrieve().body(JsonNode.class);
            return new ReleaseRef(spec.tagName(),
                    rel != null && rel.has("html_url") ? rel.get("html_url").asText() : null, false);
        } catch (RestClientResponseException e) {
            // 422 = 该 tag 的 Release 已存在 → 返回既有（幂等）
            if (e.getStatusCode().value() == 422) {
                try {
                    JsonNode rel = c.get()
                            .uri(uri(cfg, "/repos/" + repo + "/releases/tags/"
                                    + URLEncoder.encode(spec.tagName(), StandardCharsets.UTF_8)))
                            .retrieve().body(JsonNode.class);
                    return new ReleaseRef(spec.tagName(),
                            rel != null && rel.has("html_url") ? rel.get("html_url").asText() : null, true);
                } catch (Exception ex) {
                    log.warn("查询既有 Release 失败: {}", ex.getMessage());
                }
            }
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "创建 Release 失败：HTTP " + e.getStatusCode().value() + " " + extractMessage(e));
        }
    }

    /**
     * API 基址：github.com / api.github.com → https://api.github.com；
     * 其余视为 GHE → &lt;base&gt;/api/v3。
     */
    static String apiBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String host = URI.create(base.isEmpty() ? "https://github.com" : base).getHost();
        if (host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("api.github.com")
                || host.equalsIgnoreCase("www.github.com"))) {
            return "https://api.github.com";
        }
        return base + "/api/v3";
    }

    /** owner/repo 逐段编码（不编码 "/"）——与 GitLab 的整段 %2F 编码不同 */
    static String encodeRepoPath(String projectKey) {
        if (projectKey == null || !projectKey.contains("/")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "GitHub 项目 key 应为 owner/repo 形式，当前：" + projectKey);
        }
        String[] parts = projectKey.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** 完整 API URI（apiBase + 已编码的 path）——避开模板展开二次编码 */
    private URI uri(IntegrationEntity cfg, String encodedPathAndQuery) {
        return URI.create(apiBase(cfg.getBaseUrl()) + encodedPathAndQuery);
    }

    private RestClient client(String token) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** 从 GitHub 错误响应提取 message（{"message":...}），不含任何凭据 */
    private String extractMessage(RestClientResponseException e) {
        try {
            JsonNode body = mapper.readTree(e.getResponseBodyAsString());
            if (body.has("message")) {
                return body.get("message").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体
        }
        String raw = e.getResponseBodyAsString();
        return raw == null ? "" : (raw.length() <= 300 ? raw : raw.substring(0, 300));
    }
}
