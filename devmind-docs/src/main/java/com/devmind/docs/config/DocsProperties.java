package com.devmind.docs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.docs.* — 文档管理配置。正文存 DB，无外部仓库路径。
 */
@ConfigurationProperties(prefix = "devmind.docs")
public class DocsProperties {

    /** 文档操作署名（留空则取当前登录用户） */
    private String author = "devmind";

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
}
