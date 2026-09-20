package com.devmind.common.model;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-48 FR-11 OpenAI 兼容 {@code /chat/completions} 调用（vLLM / OneAPI / Ollama / OpenAI 等同协议通用）。
 *
 * <p>目前只服务「通用模型端点的连接测试」：探针问一句、拿回复文本。三个刻意选择：</p>
 * <ul>
 *   <li><b>不发 {@code max_tokens}</b>——部分网关与推理模型对它的取值直接 400，而探针只需要"有回复"；</li>
 *   <li><b>不重试</b>——探针由人盯着，重试只会双倍耗时与计费（embedding 是批量索引才需要重试）；</li>
 *   <li><b>判定宽松但不容忍空回复</b>——{@code content} 允许是文本节点或数组里的首个文本元素
 *       （OneAPI/vLLM 兼容层会这么回），不要求回显 {@code model}（网关会改写）、不要求 {@code id}；
 *       但 2xx + 空回复算失败，那几乎总是网关拦截或限流，必须让运维看见。</li>
 * </ul>
 */
public final class OpenAiCompatChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiCompatChat() {
    }

    /** 调用参数（一次调用的全部外部输入）。刻意不复用 embedding 的 Options——那边的 batchSize 对对话无意义 */
    public record Options(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    /**
     * 发一条 user 消息，返回模型回复文本（连续空白已压成单空格，截到 {@value OpenAiCompatHttp#SNIPPET_LEN} 字）。
     * 网络/非 2xx/响应结构异常一律抛 {@link ModelCallException}，消息已脱敏。
     */
    public static String chat(Options opt, String prompt) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", opt.model());
            var message = MAPPER.createObjectNode();
            message.put("role", "user");
            message.put("content", prompt);
            root.putArray("messages").add(message);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(
                            opt.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(opt.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)));
            if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
                req.header("Authorization", "Bearer " + opt.apiKey());
            }
            HttpResponse<String> resp = OpenAiCompatHttp.http(opt.timeoutSeconds())
                    .send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ModelCallException("chat 端点返回 " + resp.statusCode() + ": "
                        + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(resp.body())));
            }
            return replyOf(resp.body());
        } catch (ModelCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelCallException("对话调用被中断", e);
        } catch (Exception e) {
            // 非 JSON 的 2xx（网关错误页）与连接失败都落这里：消息必须脱敏，且不许把 Jackson 异常原文漏出去
            throw new ModelCallException("对话调用失败: " + OpenAiCompatHttp.sanitize(String.valueOf(e)), e);
        }
    }

    /** 从响应体取回复文本；结构不符/回复为空一律 {@link ModelCallException} */
    private static String replyOf(String body) {
        JsonNode choices;
        try {
            choices = MAPPER.readTree(body).path("choices");
        } catch (Exception e) {
            throw new ModelCallException("chat 响应不是合法 JSON: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelCallException("chat 响应缺少 choices: "
                    + OpenAiCompatHttp.sanitize(OpenAiCompatHttp.abbreviate(body)));
        }
        JsonNode content = choices.get(0).path("message").path("content");
        String text = content.isArray() ? firstTextOf(content) : content.asText("");
        if (text.isBlank()) {
            throw new ModelCallException("chat 响应 choices[0].message.content 为空（网关拦截或限流？）");
        }
        return collapse(text);
    }

    /** 数组形态的 content：取首个非空文本（元素可能是纯字符串，也可能是 {type:"text", text:"…"}） */
    private static String firstTextOf(JsonNode parts) {
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

    /** 回复文本要进 UI 与落库消息，先把换行/连续空白压成单空格 */
    private static String collapse(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= OpenAiCompatHttp.SNIPPET_LEN
                ? oneLine : oneLine.substring(0, OpenAiCompatHttp.SNIPPET_LEN) + "…";
    }
}
