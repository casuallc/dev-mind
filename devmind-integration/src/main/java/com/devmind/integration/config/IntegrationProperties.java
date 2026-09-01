package com.devmind.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-18 集成模块配置。
 */
@ConfigurationProperties(prefix = "devmind.integration")
public class IntegrationProperties {

    /** 凭据加密密钥（base64 或原始串）；空=复用 data/auth.key 派生，再空=自动生成 data/integration-crypto.key */
    private String cryptoKey = "";

    /** 平台 API 连接超时（毫秒） */
    private int connectTimeoutMs = 10000;

    /** 平台 API 读超时（毫秒） */
    private int readTimeoutMs = 30000;

    public String getCryptoKey() { return cryptoKey; }
    public void setCryptoKey(String cryptoKey) { this.cryptoKey = cryptoKey; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
