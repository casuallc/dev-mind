package com.devmind.integration.connector.feishu;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 飞书开放平台 API 客户端（CAP-45 FR-02）：tenant_access_token 获取+内存缓存
 * （过期前 5 分钟刷新，不持久化）；wiki 节点解析、docx 文档/blocks 分页拉取、旧版 doc raw_content。
 * token 不写日志/异常消息（异常只带飞书 code/msg）。
 */
public class FeishuApiClient {

    /** token 缓存提前量（秒）：过期前 5 分钟即刷新 */
    private static final long TOKEN_REFRESH_AHEAD_SEC = 300;
    private static final int BLOCKS_PAGE_SIZE = 500;

    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private String cachedToken;
    private Instant tokenExpireAt = Instant.EPOCH;

    public FeishuApiClient(String baseUrl, String appId, String appSecret) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.appId = appId;
        this.appSecret = appSecret;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** tenant_access_token（缓存有效直接返回） */
    public synchronized String tenantToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpireAt)) {
            return cachedToken;
        }
        var body = mapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        JsonNode resp = request("POST", "/open-apis/auth/v3/tenant_access_token/internal",
                mapper.writeValueAsString(body), null);
        ensureOk(resp, "获取 tenant_access_token");
        cachedToken = resp.path("tenant_access_token").asText();
        long expire = resp.path("expire").asLong(7200);
        tokenExpireAt = Instant.now().plusSeconds(Math.max(60, expire - TOKEN_REFRESH_AHEAD_SEC));
        return cachedToken;
    }

    /** wiki 节点解析：token → {objType, objToken, title} */
    public WikiNode getWikiNode(String nodeToken) {
        JsonNode data = get("/open-apis/wiki/v2/spaces/get_node?token=" + encode(nodeToken)
                + "&obj_type=wiki");
        JsonNode node = data.path("node");
        return new WikiNode(
                node.path("obj_type").asText(""),
                node.path("obj_token").asText(""),
                node.path("title").asText(""));
    }

    /** docx 文档元数据（取标题） */
    public String getDocxTitle(String docId) {
        JsonNode data = get("/open-apis/docx/v1/documents/" + encode(docId));
        return data.path("document").path("title").asText("");
    }

    /** docx blocks 全量分页拉取（文档顺序的扁平列表） */
    public List<JsonNode> getAllBlocks(String docId) {
        List<JsonNode> blocks = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/open-apis/docx/v1/documents/" + encode(docId) + "/blocks?page_size="
                    + BLOCKS_PAGE_SIZE + (pageToken == null ? "" : "&page_token=" + encode(pageToken));
            JsonNode data = get(path);
            data.path("items").forEach(blocks::add);
            pageToken = data.path("has_more").asBoolean(false)
                    ? data.path("page_token").asText("") : null;
        } while (pageToken != null && !pageToken.isBlank());
        return blocks;
    }

    /** 旧版 doc 纯文本 */
    public String getDocRawContent(String docToken) {
        JsonNode data = get("/open-apis/doc/v2/" + encode(docToken) + "/raw_content");
        return data.path("content").asText("");
    }

    public record WikiNode(String objType, String objToken, String title) {
    }

    // ---------------- 底层 ----------------

    /** GET + 业务 code 校验，返回 data 节点 */
    private JsonNode get(String path) {
        JsonNode resp = request("GET", path, null, tenantToken());
        ensureOk(resp, "GET " + path);
        return resp.path("data");
    }

    private JsonNode request(String method, String path, String body, String bearer) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=utf-8");
            if (bearer != null) {
                req.header("Authorization", "Bearer " + bearer);
            }
            req.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new DevMindException(ErrorCode.INTERNAL,
                        "飞书接口 HTTP " + resp.statusCode() + "（" + path + "）");
            }
            return mapper.readTree(resp.body());
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "飞书接口调用失败（" + path + "）: " + e.getMessage());
        }
    }

    private static void ensureOk(JsonNode resp, String action) {
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    action + "失败：飞书 code=" + code + " msg=" + resp.path("msg").asText(""));
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
