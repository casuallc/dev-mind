package com.devmind.knowledge.embedding;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockEmbeddingClient：确定性、单位范数、共享词余弦高于无关文本（E2E 检索可据此断言命中）。
 */
class MockEmbeddingClientTest {

    private final MockEmbeddingClient client = new MockEmbeddingClient(64);

    @Test
    void deterministicAndUnitNorm() {
        List<float[]> v1 = client.embed(List.of("前端构建规范"));
        List<float[]> v2 = new MockEmbeddingClient(64).embed(List.of("前端构建规范"));
        assertEquals(64, v1.get(0).length);
        assertArrayEquals(v1.get(0), v2.get(0), "同文同维度向量必须一致");
        double norm = 0;
        for (float x : v1.get(0)) {
            norm += (double) x * x;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-5, "非空文本向量应 L2 归一");
    }

    @Test
    void sharedTokensScoreHigher() {
        float[] query = client.embed(List.of("前端 构建 规范")).get(0);
        float[] related = client.embed(List.of("前端 构建 流程与产物说明")).get(0);
        float[] unrelated = client.embed(List.of("数据库 备份 策略")).get(0);
        double relatedScore = VectorJson.cosine(query, related);
        double unrelatedScore = VectorJson.cosine(query, unrelated);
        assertTrue(relatedScore > unrelatedScore,
                "共享词余弦应更高: related=" + relatedScore + " unrelated=" + unrelatedScore);
    }

    @Test
    void emptyTextIsZeroVector() {
        float[] v = client.embed(List.of("")).get(0);
        assertEquals(0, VectorJson.cosine(v, v), "零向量余弦按 0 处理");
    }

    @Test
    void vectorJsonRoundTrip() {
        float[] v = {0.5f, -0.25f, 0f, 1.0e-3f};
        float[] parsed = VectorJson.parse(VectorJson.toJson(v));
        assertArrayEquals(v, parsed, 1e-6f, "向量 JSON 序列化往返");
        org.junit.jupiter.api.Assertions.assertNull(VectorJson.parse("非JSON"), "非法输入返回 null");
        assertEquals(0, VectorJson.cosine(new float[]{1, 2}, new float[]{1}), "维度不一致余弦 0");
    }
}
