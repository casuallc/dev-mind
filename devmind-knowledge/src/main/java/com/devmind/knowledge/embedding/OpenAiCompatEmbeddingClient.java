package com.devmind.knowledge.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 /embeddings 端点客户端（vLLM/OneAPI/Ollama 等同协议服务通用）。
 * POST {baseUrl}/embeddings {"model": ..., "input": [...]} → data[].embedding。
 */
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatEmbeddingClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/embeddings";
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            var root = mapper.createObjectNode();
            root.put("model", model);
            var input = root.putArray("input");
            texts.forEach(input::add);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new EmbeddingException("embedding 端点返回 " + resp.statusCode() + ": "
                        + abbreviate(resp.body()));
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new EmbeddingException("embedding 响应 data 条数与输入不符: expect="
                        + texts.size() + " actual=" + (data.isArray() ? data.size() : "非数组"));
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                vectors.add(v);
            }
            return vectors;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("embedding 调用失败: " + e, e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
