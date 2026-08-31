package com.devmind.docs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.docs.* — 文档管理配置。repo-path 为本机 docs-repo 路径（写 application-local.yml，不随仓库提交）。
 */
@ConfigurationProperties(prefix = "devmind.docs")
public class DocsProperties {

    /** docs-repo 本地 git 仓库路径；空 = 文档存储禁用（API 返回 503/明确报错） */
    private String repoPath = "";
    private boolean enabled = true;
    /** git 提交署名 */
    private String author = "devmind";

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
}
