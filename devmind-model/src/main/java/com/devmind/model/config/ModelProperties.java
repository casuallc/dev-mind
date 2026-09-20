package com.devmind.model.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-48 devmind.model.* — 模型接入模块配置。
 */
@ConfigurationProperties(prefix = "devmind.model")
public class ModelProperties {

    /**
     * 端点凭据加密密钥（base64 或原始串）；空 = 复用 data/auth.key 派生，
     * 再空 = 自动生成 data/model-crypto.key。与 CAP-18 集成凭据同主密钥、不同域。
     */
    private String cryptoKey = "";

    public String getCryptoKey() { return cryptoKey; }
    public void setCryptoKey(String cryptoKey) { this.cryptoKey = cryptoKey; }
}
