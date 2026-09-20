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
                // 钉死 HTTP/1.1：默认模式会先尝试 h2c 升级（请求带 Upgrade: h2c + HTTP2-Settings），
                // 而 vLLM 0.28 的 uvicorn 在见到升级提议时会把 POST body 丢掉，
                // 报 400「body Field required, input None」（2026-09-20 真机实锤：同一请求去掉这三个头即 200）。
                // 这些 OpenAI 兼容端点本来就是 HTTP/1.1 服务，升级永远协商不上，只会有害。
                .version(HttpClient.Version.HTTP_1_1)
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

    /**
     * 非 2xx 的统一诊断串。<b>带上实际请求的 URL</b>：「baseUrl 填短了」是最常见的一类误配
     * （如 {@code http://host:8000} 少了 {@code /v1}，FastAPI 系的 vLLM/SGLang 就回
     * {@code {"detail":"Not Found"}}），不回显 URL 只能靠用户猜。
     *
     * <p>404 再补一句两类成因：路径不对，或该服务上没有这个模型名——vLLM 对未知 model 也回 404，
     * 只报「404」会把人引向错误的方向（去查网络，其实是模型名拼错）。</p>
     */
    static String failure(String what, java.net.URI uri, int status, String body) {
        String hint = status == 404
                ? "（看上面的地址：OpenAI 兼容服务多数要求 baseUrl 带 /v1 前缀；地址没错则是该服务上没有这个模型名）"
                : "";
        return what + " " + uri + " 返回 " + status + ": " + sanitize(abbreviate(body)) + hint;
    }
}
