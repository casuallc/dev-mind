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
    private final Index index = new Index();
    private final Upload upload = new Upload();
    private final Enrich enrich = new Enrich();
    private final Eval eval = new Eval();

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Embedding getEmbedding() { return embedding; }
    public Index getIndex() { return index; }
    public Upload getUpload() { return upload; }
    public Enrich getEnrich() { return enrich; }
    public Eval getEval() { return eval; }

    /**
     * CAP-85 FR-28：模型自动分类/摘要。默认关闭——模型调用有成本且分类需人工抽检，
     * 只有显式开启才在摄入/同步后自动排队；手工触发管理端接口不受 enabled 限制。
     */
    public static class Enrich {
        /** 自动增强开关（关 = 摄入后不自动提交任务） */
        private boolean enabled = false;
        /** CHAT 端点 ID；空 = 平台默认 CHAT 端点 */
        private Long endpointId;
        /** 送入模型的正文上限（字符），防止长文档撑爆上下文与成本 */
        private int maxInputChars = 4000;
        /** 单次任务处理条目数上限 */
        private int batchLimit = 20;
        private int timeoutSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Long getEndpointId() { return endpointId; }
        public void setEndpointId(Long endpointId) { this.endpointId = endpointId; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
        public int getBatchLimit() { return batchLimit; }
        public void setBatchLimit(int batchLimit) { this.batchLimit = batchLimit; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /**
     * devmind.knowledge.eval.* — CAP-85 FR-30 检索评测。数据集入仓，改动索引/模型前后
     * 跑同一份即可对比；路径相对运行目录（分发包下可覆盖为绝对路径）。
     */
    public static class Eval {
        private String datasetPath = "tests/knowledge_eval/dataset.json";
        private int topK = 10;

        public String getDatasetPath() { return datasetPath; }
        public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    /** CAP-84 FR-09：嵌入式 Lucene 索引参数。 */
    public static class Index {
        /** 索引目录（相对运行目录；分发包下即 data/knowledge-index） */
        private String path = "data/knowledge-index";
        /** IndexWriter 堆内缓冲；2GB 机器建议 32~64 */
        private int ramBufferMb = 64;
        /** 每批 commit 最多写入文档数（批量导入时控制段合并压力） */
        private int commitEveryChunks = 200;
        /** 单次检索最多取候选块数（BM25/向量各自） */
        private int candidateTopK = 50;
        /** FR-11 融合分下限（RRF 文档分；0 = 不过滤）。默认 0.008 ≈ 单通道前 65 名以内 */
        private double minScore = 0.0d;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getRamBufferMb() { return ramBufferMb; }
        public void setRamBufferMb(int ramBufferMb) { this.ramBufferMb = ramBufferMb; }
        public int getCommitEveryChunks() { return commitEveryChunks; }
        public void setCommitEveryChunks(int commitEveryChunks) { this.commitEveryChunks = commitEveryChunks; }
        public int getCandidateTopK() { return candidateTopK; }
        public void setCandidateTopK(int candidateTopK) { this.candidateTopK = candidateTopK; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }
    }

    /** CAP-84 FR-02：本地上传限额与落盘目录。 */
    public static class Upload {
        /** 原始文件目录（不对外托管） */
        private String filesPath = "data/knowledge-files";
        /** 上传临时目录（任务结束后清理） */
        private String stagingPath = "data/knowledge-files/.staging";
        private long maxFileMb = 50;
        private int maxBatchFiles = 200;
        private long maxBatchMb = 500;
        /** ZIP 解压比上限（防压缩炸弹） */
        private int maxZipRatio = 100;
        /** 单文档解析字符上限（防超大文本撑爆索引/上下文） */
        private int maxParsedChars = 4_000_000;
        /** 历史版本保留数 */
        private int versionRetention = 20;

        public String getFilesPath() { return filesPath; }
        public void setFilesPath(String filesPath) { this.filesPath = filesPath; }
        public String getStagingPath() { return stagingPath; }
        public void setStagingPath(String stagingPath) { this.stagingPath = stagingPath; }
        public long getMaxFileMb() { return maxFileMb; }
        public void setMaxFileMb(long maxFileMb) { this.maxFileMb = maxFileMb; }
        public int getMaxBatchFiles() { return maxBatchFiles; }
        public void setMaxBatchFiles(int maxBatchFiles) { this.maxBatchFiles = maxBatchFiles; }
        public long getMaxBatchMb() { return maxBatchMb; }
        public void setMaxBatchMb(long maxBatchMb) { this.maxBatchMb = maxBatchMb; }
        public int getMaxZipRatio() { return maxZipRatio; }
        public void setMaxZipRatio(int maxZipRatio) { this.maxZipRatio = maxZipRatio; }
        public int getMaxParsedChars() { return maxParsedChars; }
        public void setMaxParsedChars(int maxParsedChars) { this.maxParsedChars = maxParsedChars; }
        public int getVersionRetention() { return versionRetention; }
        public void setVersionRetention(int versionRetention) { this.versionRetention = versionRetention; }
    }

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
