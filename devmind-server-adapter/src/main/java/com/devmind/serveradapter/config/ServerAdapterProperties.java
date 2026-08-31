package com.devmind.serveradapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-07 配置（devmind.server-adapter）。
 * cryptoKey 留空时自动生成并持久化到 data/server-crypto.key（gitignored），保证重启后可解密。
 */
@ConfigurationProperties(prefix = "devmind.server-adapter")
public class ServerAdapterProperties {

    /** 凭证加密密钥（32 字节 base64）；空=自动生成持久化 */
    private String cryptoKey = "";

    /** 连接/命令执行超时（毫秒） */
    private long connectTimeoutMs = 15000;

    /** 是否记录执行审计 */
    private boolean auditEnabled = true;

    public String getCryptoKey() { return cryptoKey; }
    public void setCryptoKey(String cryptoKey) { this.cryptoKey = cryptoKey; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public boolean isAuditEnabled() { return auditEnabled; }
    public void setAuditEnabled(boolean auditEnabled) { this.auditEnabled = auditEnabled; }
}
