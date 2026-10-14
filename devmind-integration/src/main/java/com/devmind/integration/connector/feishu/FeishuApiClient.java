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
        this(baseUrl, appId, appSecret, null);
    }

    /**
     * @param proxySelector CAP-70 FR-06：规则驱动出口选择器（{@code EgressProxySelector}），
     *                      null = 直连零行为变化。显式挂载、不设 JVM 全局默认。
     */
    public FeishuApiClient(String baseUrl, String appId, String appSecret,
                           java.net.ProxySelector proxySelector) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.appId = appId;
        this.appSecret = appSecret;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.http = builder.build();
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

    // ---------------- CAP-75：机器人消息回推 ----------------

    /** 回复指定消息（im/v1 reply）：机器人回答回推主路径。 */
    public void replyText(String messageId, String text) {
        JsonNode resp = request("POST", "/open-apis/im/v1/messages/" + encode(messageId) + "/reply",
                textBody(null, text), tenantToken());
        ensureOk(resp, "回复消息");
    }

    /** 主动发文本消息（reply 锚点缺失时的兜底，如重连后补推）。 */
    public void sendText(String receiveId, String receiveIdType, String text) {
        JsonNode resp = request("POST",
                "/open-apis/im/v1/messages?receive_id_type=" + encode(receiveIdType),
                textBody(receiveId, text), tenantToken());
        ensureOk(resp, "发送消息");
    }

    // ---------------- CAP-75 FR-16：图片消息（出站） ----------------

    /**
     * 上传图片（multipart POST /im/v1/images，image_type=message）→ image_key。
     * 需 `im:resource` 权限（缺则 code=99991672 类鉴权错误，改权限须重新发布版本）。
     */
    public String uploadImage(byte[] bytes, String fileName) {
        String boundary = "----devmind" + Long.toHexString(System.nanoTime());
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image_type\"\r\n\r\nmessage\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\""
                + fileName.replace("\"", "_") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
        JsonNode resp = requestRaw("POST", "/open-apis/im/v1/images", body,
                "multipart/form-data; boundary=" + boundary, tenantToken());
        ensureOk(resp, "上传图片");
        return resp.path("data").path("image_key").asText();
    }

    /** 回复图片消息（image_key 须先经 {@link #uploadImage} 取得）。 */
    public void replyImage(String messageId, String imageKey) {
        JsonNode resp = request("POST", "/open-apis/im/v1/messages/" + encode(messageId) + "/reply",
                imageBody(null, imageKey), tenantToken());
        ensureOk(resp, "回复图片消息");
    }

    /** 主动发图片消息（reply 锚点缺失时的兜底，与 sendText 同口径）。 */
    public void sendImage(String receiveId, String receiveIdType, String imageKey) {
        JsonNode resp = request("POST",
                "/open-apis/im/v1/messages?receive_id_type=" + encode(receiveIdType),
                imageBody(receiveId, imageKey), tenantToken());
        ensureOk(resp, "发送图片消息");
    }

    /** 图片消息体：与 textBody 同一契约——content 必须是内嵌 JSON 字符串。 */
    private String imageBody(String receiveId, String imageKey) {
        var body = mapper.createObjectNode();
        if (receiveId != null) {
            body.put("receive_id", receiveId);
        }
        body.put("msg_type", "image");
        var content = mapper.createObjectNode();
        content.put("image_key", imageKey);
        body.put("content", mapper.writeValueAsString(content));
        return mapper.writeValueAsString(body);
    }

    // ---------------- CAP-75 FR-17：图片下载（入站） ----------------

    /**
     * 下载用户发给机器人的图片（GET /im/v1/images/{key}）：成功返回原始字节，
     * 失败（权限/过期）飞书回 JSON——非 2xx 或 content-type 为 json 时按错误抛出。
     */
    public byte[] downloadImage(String imageKey) {
        String path = "/open-apis/im/v1/images/" + encode(imageKey);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + tenantToken())
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String type = resp.headers().firstValue("Content-Type").orElse("");
            if (resp.statusCode() / 100 != 2 || type.contains("json")) {
                throw new DevMindException(ErrorCode.INTERNAL,
                        "下载图片失败：飞书 HTTP " + resp.statusCode()
                                + truncateBody(new String(resp.body(), StandardCharsets.UTF_8)));
            }
            return resp.body();
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "飞书接口调用失败（" + baseUrl + path + "）: " + e.getMessage());
        }
    }

    /**
     * 消息体拼装：飞书 im/v1 的 content 必须是**内嵌 JSON 字符串**
     * （{"msg_type":"text","content":"{\"text\":\"…\"}"}），发对象形态真机必拒
     * （E2E mock 曾照单全收掩盖了这一点，勿退回对象形态）。
     */
    private String textBody(String receiveId, String text) {
        var body = mapper.createObjectNode();
        if (receiveId != null) {
            body.put("receive_id", receiveId);
        }
        body.put("msg_type", "text");
        var content = mapper.createObjectNode();
        content.put("text", text);
        body.put("content", mapper.writeValueAsString(content));
        return mapper.writeValueAsString(body);
    }

    // ---------------- 底层 ----------------

    /** GET + 业务 code 校验，返回 data 节点 */
    private JsonNode get(String path) {
        JsonNode resp = request("GET", path, null, tenantToken());
        ensureOk(resp, "GET " + path);
        return resp.path("data");
    }

    private JsonNode request(String method, String path, String body, String bearer) {
        return requestRaw(method, path,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8", bearer);
    }

    /** 原始字节请求（JSON 与 multipart 共用）；非 2xx 带有效 URL+返回体截断抛错。 */
    private JsonNode requestRaw(String method, String path, byte[] body, String contentType,
                                String bearer) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType);
            if (bearer != null) {
                req.header("Authorization", "Bearer " + bearer);
            }
            req.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // 带上有效 URL 与返回体截断：HTTP 层失败（多非飞书业务 code，而是 baseUrl 配错
                // 指到 webhook/网关对未知路径的 400）只有返回体能区分根因，不带就是盲猜
                throw new DevMindException(ErrorCode.INTERNAL,
                        "飞书接口 HTTP " + resp.statusCode() + "（" + baseUrl + path + "）"
                                + truncateBody(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            // 出口代理/隧道失败走这里（ProxySelector 内的 fail-visible 异常被 JDK 包进连接异常链），
            // 带上有效 URL 便于分辨「打到哪去了」
            throw new DevMindException(ErrorCode.INTERNAL,
                    "飞书接口调用失败（" + baseUrl + path + "）: " + e.getMessage());
        }
    }

    private static void ensureOk(JsonNode resp, String action) {
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    action + "失败：飞书 code=" + code + " msg=" + resp.path("msg").asText(""));
        }
    }

    /** 错误消息附返回体截断（空体不附；上限防 512 列被无意义长体挤掉 URL） */
    private static String truncateBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return ": " + (trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
