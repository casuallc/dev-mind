package com.devmind.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * CAP-48 FR-01 模型端点（一等平台资源）：一个可调用的模型服务实例。
 *
 * <p>凭据以 {@code enc1:} AES-GCM 密文落 {@code api_key_enc}，任何视图不回显明文；
 * {@code dimensions} 是<b>连接测试实测探测的产物</b>，不接受人工提交——人工填错维度正是
 * FR-06 要防的事故源（换模型维度变化会让余弦恒 0、阈值过滤成"无命中"）。</p>
 *
 * <p>红线：{@code is_default}/{@code last_test_ok} 是布尔列 → <b>禁 @ColumnDefault</b>
 * （MySQL bit 列不接受 default 'false' 建列），靠实体初始值 + getter 兜底。</p>
 */
@Entity
@Table(name = "model_endpoints")
public class ModelEndpointEntity {

    public static final String KIND_EMBEDDING = "EMBEDDING";
    public static final String KIND_CHAT = "CHAT";
    public static final String KIND_RERANK = "RERANK";

    public static final String PROVIDER_OPENAI = "openai-compatible";
    public static final String PROVIDER_MOCK = "mock";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** EMBEDDING / CHAT / RERANK（本期只实现 EMBEDDING，其余值 400 预留） */
    @Column(nullable = false, length = 16)
    private String kind = KIND_EMBEDDING;

    @Column(nullable = false, length = 128)
    private String name;

    /** openai-compatible | mock */
    @Column(nullable = false, length = 32)
    private String provider = PROVIDER_OPENAI;

    /** OpenAI 兼容服务根地址（如 https://api.openai.com/v1）；mock 可空 */
    @Column(name = "base_url", length = 512)
    private String baseUrl;

    /** enc1: 密文（AES-GCM，域 devmind-model）；永不明文回显 */
    @Column(name = "api_key_enc", length = 1024)
    private String apiKeyEnc;

    @Column(length = 128)
    private String model;

    /** 连接测试探测写入；null = 尚未探测过 */
    private Integer dimensions;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    @Column(name = "batch_size", nullable = false)
    private int batchSize = 32;

    /** 检索条数覆盖（null = 用平台默认） */
    @Column(name = "top_k")
    private Integer topK;

    /** 余弦阈值覆盖（null = 用平台默认；不同模型的合理阈值差异极大） */
    private Double threshold;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    /** null = 从未测试过 */
    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 1000)
    private String lastTestMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public Instant getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(Instant lastTestAt) { this.lastTestAt = lastTestAt; }
    public Boolean getLastTestOk() { return lastTestOk; }
    public void setLastTestOk(Boolean lastTestOk) { this.lastTestOk = lastTestOk; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean mock() {
        return PROVIDER_MOCK.equalsIgnoreCase(provider);
    }

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
