package com.devmind.common.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-48 FR-05 OpenAI 兼容 {@code /embeddings} 调用（vLLM / OneAPI / Ollama / OpenAI 等同协议通用）。
 * 放在 common 是因为有两个独立消费方，且它们**在"报错时不能泄密"这一点上不能各写一套**：
 * devmind-model 的连接测试（探测维度）与 devmind-knowledge 的索引/检索向量化。
 *
 * <p>职责：分批、单批失败重试一次（500ms 退避）、请求超时读端点配置、响应结构校验
 * （data 条数与输入一致 + 所有向量等长）、错误消息脱敏。</p>
 *
 * <p>{@code HttpClient} 按超时值静态复用——每次调用新建会把连接池也一起废掉。</p>
 */
public final class OpenAiCompatEmbeddings {

    /** 单批失败重试次数（首次 + 重试 1 次） */
    private static final int ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 500;
    /** 响应体摘要长度（错误消息里带的原文片段） */
    private static final int SNIPPET_LEN = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Integer, HttpClient> HTTP = new ConcurrentHashMap<>();
    /** 形如 sk-xxx / Bearer xxx 的凭据片段，出现在任何回显文本里都要抹掉 */
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[A-Za-z0-9_\\-]{6,}|bearer\\s+\\S+)");

    private OpenAiCompatEmbeddings() {
    }

    /** 调用参数（一次调用的全部外部输入） */
    public record Options(String baseUrl, String apiKey, String model, int timeoutSeconds, int batchSize) {
    }

    /**
     * 批量向量化：返回与 texts 等长、同序的向量列表；空输入返回空列表（不发请求）。
     * 任一批次重试后仍失败则抛 {@link EmbeddingCallException}，消息含批次区间便于定位。
     */
    public static List<float[]> embed(Options opt, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batch = Math.max(1, opt.batchSize());
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batch) {
            int to = Math.min(from + batch, texts.size());
            out.addAll(embedBatch(opt, texts.subList(from, to), from, to));
        }
        return out;
    }

    private static List<float[]> embedBatch(Options opt, List<String> batch, int from, int to) {
        EmbeddingCallException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return embedOnce(opt, batch);
            } catch (EmbeddingCallException e) {
                last = e;
                if (attempt == 0) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new EmbeddingCallException(
                "embedding 调用失败 chunks[" + from + "," + to + "): " + last.getMessage(), last);
    }

    private static List<float[]> embedOnce(Options opt, List<String> texts) {
        try {
            var root = MAPPER.createObjectNode();
            root.put("model", opt.model());
            var input = root.putArray("input");
            texts.forEach(input::add);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(
                            opt.baseUrl().replaceAll("/+$", "") + "/embeddings"))
                    .timeout(Duration.ofSeconds(opt.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)));
            if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
                req.header("Authorization", "Bearer " + opt.apiKey());
            }
            HttpResponse<String> resp = http(opt.timeoutSeconds())
                    .send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new EmbeddingCallException("embedding 端点返回 " + resp.statusCode() + ": "
                        + sanitize(abbreviate(resp.body())));
            }
            JsonNode data = MAPPER.readTree(resp.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new EmbeddingCallException("embedding 响应 data 条数与输入不符: expect="
                        + texts.size() + " actual=" + (data.isArray() ? data.size() : "非数组"));
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            int dim = -1;
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                if (v.length == 0) {
                    throw new EmbeddingCallException("embedding 响应含空向量");
                }
                if (dim < 0) {
                    dim = v.length;
                } else if (v.length != dim) {
                    throw new EmbeddingCallException("embedding 响应维度不一致: " + dim + " vs " + v.length);
                }
                vectors.add(v);
            }
            return vectors;
        } catch (EmbeddingCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingCallException("embedding 调用被中断", e);
        } catch (Exception e) {
            throw new EmbeddingCallException("embedding 调用失败: " + sanitize(String.valueOf(e)), e);
        }
    }

    private static HttpClient http(int timeoutSeconds) {
        return HTTP.computeIfAbsent(timeoutSeconds, t -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(t))
                .build());
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= SNIPPET_LEN ? body : body.substring(0, SNIPPET_LEN) + "…";
    }

    /** 抹掉可能被端点回显的密钥片段（FR-02：异常消息不得含 apiKey） */
    public static String sanitize(String text) {
        return text == null ? "" : SECRET.matcher(text).replaceAll("***");
    }
}
