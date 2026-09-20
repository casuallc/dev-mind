package com.devmind.knowledge.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mock embedding（devmind.knowledge.embedding.provider=mock）：确定性哈希词袋向量，
 * 无需外部服务即可跑通索引/检索链路（测试与 E2E 用，仿 runner executor=fake 先例）。
 * 分词：ascii 词 + 单字 CJK；向量 = 词哈希累加后 L2 归一——共享词越多余弦越高。
 */
public class MockEmbeddingClient implements EmbeddingClient {

    /** 模型名取 common 的跨模块合同值（索引血缘要与端点侧逐字一致，禁止各写一份） */
    public static final String MODEL = com.devmind.common.model.ModelEndpointView.MODEL_MOCK;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]");

    private final int dimensions;

    public MockEmbeddingClient(int dimensions) {
        this.dimensions = Math.max(8, dimensions);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorize(text));
        }
        return vectors;
    }

    private float[] vectorize(String text) {
        float[] v = new float[dimensions];
        if (text != null) {
            Matcher m = TOKEN.matcher(text.toLowerCase());
            while (m.find()) {
                int h = m.group().hashCode();
                int idx = (h & 0x7fffffff) % dimensions;
                v[idx] += (h & 0x40000000) != 0 ? -1f : 1f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }
}
