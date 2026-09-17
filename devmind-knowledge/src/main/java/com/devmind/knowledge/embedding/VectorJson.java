package com.devmind.knowledge.embedding;

/**
 * 向量 JSON 序列化（knowledge_chunks.embedding 列为 JSON float 数组文本，H2/PG 通用，不依赖 pgvector）。
 * 写用 StringBuilder（热路径免 Jackson）；读用 Jackson 容错解析。
 */
public final class VectorJson {

    private VectorJson() {
    }

    public static String toJson(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** 解析 JSON float 数组；非法/空返回 null（调用方按 0 分处理） */
    public static float[] parse(String json) {
        if (json == null || json.length() < 2) {
            return null;
        }
        try {
            var arr = new tools.jackson.databind.ObjectMapper().readTree(json);
            if (!arr.isArray()) {
                return null;
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度；任一向量为 null/零范数/维度不一致返回 0 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
