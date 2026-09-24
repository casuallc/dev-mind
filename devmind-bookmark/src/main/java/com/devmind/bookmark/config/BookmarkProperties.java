package com.devmind.bookmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.bookmark.* — CAP-64 收藏夹配置。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.bookmark")
public class BookmarkProperties {

    /**
     * 账号密码加密密钥（base64 或原始串）；空 = 复用 data/auth.key 派生，
     * 再空 = 自动生成 data/bookmark-crypto.key。与 CAP-18/CAP-48 同主密钥、不同域。
     */
    private String cryptoKey = "";

    private final Probe probe = new Probe();

    public String getCryptoKey() { return cryptoKey; }
    public void setCryptoKey(String cryptoKey) { this.cryptoKey = cryptoKey; }

    public Probe getProbe() { return probe; }

    /** FR-04 可用性探测参数 */
    public static class Probe {

        /**
         * 是否允许探测私有地址段 / localhost。默认 true——内部系统恰是收藏夹的主要对象。
         * 置 false 时这些地址直接判 FAIL（code=PRIVATE），不起出站连接。
         */
        private boolean allowPrivate = true;

        /** 单次探测超时（秒） */
        private int timeoutSeconds = 10;

        /** 最多跟随的重定向次数 */
        private int maxRedirects = 3;

        /** 批量探测一次上限（超出 400） */
        private int batchLimit = 200;

        public boolean isAllowPrivate() { return allowPrivate; }
        public void setAllowPrivate(boolean allowPrivate) { this.allowPrivate = allowPrivate; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMaxRedirects() { return maxRedirects; }
        public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }

        public int getBatchLimit() { return batchLimit; }
        public void setBatchLimit(int batchLimit) { this.batchLimit = batchLimit; }
    }
}
