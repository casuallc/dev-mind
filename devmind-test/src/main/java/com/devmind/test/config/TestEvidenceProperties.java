package com.devmind.test.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-104 证据存储配置：MVP 小文件 marker 上限（单文件/单运行），超限提示走 M3 大文件上传。
 * 主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.test.evidence")
public class TestEvidenceProperties {

    /** 证据根目录；空 = ${user.dir}/data/test-evidence */
    private String rootDir = "";

    /** 单证据文件上限（字节，默认 2MB） */
    private long maxFileBytes = 2L * 1024 * 1024;

    /** 单次运行证据总量上限（字节，默认 20MB） */
    private long maxRunBytes = 20L * 1024 * 1024;

    /** M3 大文件上行：单文件上限（字节，默认 50MB） */
    private long maxUploadBytes = 50L * 1024 * 1024;

    /** M3 大文件上行：单运行总量上限（字节，默认 200MB） */
    private long maxRunUploadBytes = 200L * 1024 * 1024;

    /** M3 一次性上传 token 有效期（秒，默认 30 分钟） */
    private long uploadTokenTtlSeconds = 1800;

    /** M3 单运行最多上传次数（限流，默认 100） */
    private int maxUploadsPerRun = 100;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public long getMaxRunBytes() { return maxRunBytes; }
    public void setMaxRunBytes(long maxRunBytes) { this.maxRunBytes = maxRunBytes; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public long getMaxRunUploadBytes() { return maxRunUploadBytes; }
    public void setMaxRunUploadBytes(long maxRunUploadBytes) { this.maxRunUploadBytes = maxRunUploadBytes; }
    public long getUploadTokenTtlSeconds() { return uploadTokenTtlSeconds; }
    public void setUploadTokenTtlSeconds(long uploadTokenTtlSeconds) { this.uploadTokenTtlSeconds = uploadTokenTtlSeconds; }
    public int getMaxUploadsPerRun() { return maxUploadsPerRun; }
    public void setMaxUploadsPerRun(int maxUploadsPerRun) { this.maxUploadsPerRun = maxUploadsPerRun; }
}
