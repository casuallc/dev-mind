package com.devmind.common.model;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * CAP-48 模型调用共用的 HTTP 与脱敏助手。包内可见——调用方只可能是本包的 OpenAI 兼容客户端。
 *
 * <p>抽出来不是为了省几行，而是<b>脱敏正则只许存在一份</b>：它一旦被复制，密钥就会从第二条路径
 * 进 {@code last_test_message}（会落库）与 HTTP 响应，这正是 FR-02 要防的事。</p>
 */
final class OpenAiCompatHttp {

    /** 响应体摘要长度（错误消息里带的原文片段） */
    static final int SNIPPET_LEN = 200;

    /** 按超时值静态复用——每次调用新建会把连接池也一起废掉 */
    private static final Map<Integer, HttpClient> HTTP = new ConcurrentHashMap<>();

    /** 形如 sk-xxx / Bearer xxx 的凭据片段，出现在任何回显文本里都要抹掉 */
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[A-Za-z0-9_\\-]{6,}|bearer\\s+\\S+)");

    private OpenAiCompatHttp() {
    }

    static HttpClient http(int timeoutSeconds) {
        return HTTP.computeIfAbsent(timeoutSeconds, t -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(t))
                .build());
    }

    /** 抹掉可能被端点回显的密钥片段（FR-02：异常消息不得含 apiKey） */
    static String sanitize(String text) {
        return text == null ? "" : SECRET.matcher(text).replaceAll("***");
    }

    static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= SNIPPET_LEN ? body : body.substring(0, SNIPPET_LEN) + "…";
    }
}
