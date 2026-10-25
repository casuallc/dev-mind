package com.devmind.changelog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-87 更新日志配置（devmind.changelog.*）。
 * path 为空时按「user.dir/CHANGELOG.md → user.dir/config/CHANGELOG.md → classpath」解析。
 */
@ConfigurationProperties(prefix = "devmind.changelog")
public class ChangelogProperties {

    private String path = "";

    /** 单文件上限（KB），防止误把构建产物/日志塞进来拖垮解析。 */
    private int maxSizeKb = 2048;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getMaxSizeKb() { return maxSizeKb; }
    public void setMaxSizeKb(int maxSizeKb) { this.maxSizeKb = maxSizeKb; }
}
