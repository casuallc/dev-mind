package com.devmind.common.model;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /**
     * 从一个 {@code content} 节点取<b>全部</b>文本：文本节点直接取；数组则把所有 part 的文本<b>拼接</b>
     * （元素可能是纯字符串，也可能是 {@code {type:"text", text:"…"}}）。
     *
     * <p>正文通道用这个（CAP-49）；截断/丢弃 part 在这里就是内容损坏，探针那套"只取首个"的语义见
     * {@link #firstTextOf}。</p>
     */
    static String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText("");
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.isTextual()) {
                sb.append(part.asText(""));
                continue;
            }
            sb.append(part.path("text").asText(""));
        }
        return sb.toString();
    }

    /** 数组形态的 content：取首个非空文本（探针语义——只要"有回复"） */
    static String firstTextOf(JsonNode parts) {
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (text.isBlank()) {
                text = part.asText("");
            }
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    /**
     * 从<b>非流式</b>响应体里取正文（{@code choices[0].message.content}，拼接版）；结构不符一律空串。
     * 只有 CAP-49 的「服务端忽略了 {@code stream:true}」回落路径用它。
     */
    static String messageText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode choices = MAPPER.readTree(body).path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            return contentText(choices.get(0).path("message").path("content"));
        } catch (Exception e) {
            return "";
        }
    }

    /** 要进 UI 与落库消息的短文本：换行/连续空白压成单空格，并截到 {@value #SNIPPET_LEN} 字 */
    static String collapse(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= SNIPPET_LEN ? oneLine : oneLine.substring(0, SNIPPET_LEN) + "…";
    }
}
