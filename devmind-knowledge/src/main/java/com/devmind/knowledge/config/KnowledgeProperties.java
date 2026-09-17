package com.devmind.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.knowledge.* — 知识库配置。repo-path 为历史字段（原 MVP 本地目录用），
 * CAP-04 起以 DB 条目为准，repo-path 不再使用。
 * CAP-44 起 embedding.* 配置向量检索（OpenAI 兼容端点；密钥写 application-local.yml 禁入库）。
 */
@ConfigurationProperties(prefix = "devmind.knowledge")
public class KnowledgeProperties {

    /** 历史字段：knowledge-repo 本地路径（原 LocalDirInjector 用），CAP-04 起不再使用 */
    private String repoPath = "";
    private boolean enabled = true;

    private final Embedding embedding = new Embedding();

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Embedding getEmbedding() { return embedding; }

    /**
     * devmind.knowledge.embedding.* — CAP-44 FR-05 向量检索配置。
     * provider 为空（且 baseUrl/model 未配）= 未配置，索引标 disabled、检索降级 LIKE。
     */
    public static class Embedding {

        /** openai-compatible | mock（确定性哈希向量，测试/E2E 用）；空 = 自动按 baseUrl+model 判断 */
        private String provider = "";
        /** OpenAI 兼容服务地址（/embeddings 端点的根，如 https://api.openai.com/v1） */
        private String baseUrl = "";
        /** 密钥（写 application-local.yml，禁入库禁提交） */
        private String apiKey = "";
        /** embedding 模型名 */
        private String model = "";
        /** mock provider 向量维度（openai 以响应为准） */
        private int dimensions = 64;
        /** 分块大小（字符数） */
        private int chunkSize = 800;
        /** 分块重叠（字符数） */
        private int chunkOverlap = 100;
        /** 检索默认返回条数 */
        private int topK = 8;
        /** 余弦相似度阈值（低于丢弃） */
        private double threshold = 0.15;
        /** embedding 远程调用超时（秒） */
        private int timeoutSeconds = 30;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
