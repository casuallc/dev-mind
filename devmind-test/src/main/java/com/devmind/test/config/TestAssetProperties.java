package com.devmind.test.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CAP-104 测试资产存储：ZIP/文件落本地目录，限制上传大小与解包规模防 zip 炸弹。 */
@ConfigurationProperties(prefix = "devmind.test.assets")
public class TestAssetProperties {

    /** 资产根目录；空 = ${user.dir}/data/test-assets */
    private String rootDir = "";

    /** 单次上传原始文件上限（字节，默认 50MB） */
    private long maxUploadBytes = 50L * 1024 * 1024;

    /** ZIP 解包后总大小上限（默认 200MB） */
    private long maxUncompressedBytes = 200L * 1024 * 1024;

    /** ZIP 解包文件数上限 */
    private int maxFiles = 2000;

    /** 解包单文件大小上限（默认 20MB） */
    private long maxFileBytes = 20L * 1024 * 1024;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public long getMaxUncompressedBytes() { return maxUncompressedBytes; }
    public void setMaxUncompressedBytes(long maxUncompressedBytes) { this.maxUncompressedBytes = maxUncompressedBytes; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
}
