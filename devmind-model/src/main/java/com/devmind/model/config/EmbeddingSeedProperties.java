package com.devmind.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-48 FR-07 一次性种子读取：CAP-44 的 {@code devmind.knowledge.embedding.*} 配置。
 *
 * <p>CAP-48 起端点表是唯一事实源，这份配置<b>只在首次启动（端点表为空）时被读一次</b>，
 * 用来生成一条端点，让存量部署零改造升级；此后用户在 UI 的修改不再被重启覆盖。
 * 之所以在本模块重新绑定同一个前缀，是因为 devmind-model 不能依赖 devmind-knowledge
 * （反向依赖）；两份绑定互不干扰。</p>
 */
@ConfigurationProperties(prefix = "devmind.knowledge.embedding")
public class EmbeddingSeedProperties {

    /** openai-compatible | mock；空 = 按 baseUrl+model 是否齐备判定 */
    private String provider = "";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    /** mock provider 的向量维度（迁移时直接落为端点维度） */
    private int dimensions = 64;

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
}
