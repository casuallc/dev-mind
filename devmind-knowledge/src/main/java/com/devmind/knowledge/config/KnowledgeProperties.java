package com.devmind.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.knowledge.* — 知识库配置。repo-path 为历史字段（原 MVP 本地目录用），
 * CAP-04 起以 DB 条目为准，repo-path 不再使用。
 */
@ConfigurationProperties(prefix = "devmind.knowledge")
public class KnowledgeProperties {

    /** 历史字段：knowledge-repo 本地路径（原 LocalDirInjector 用），CAP-04 起不再使用 */
    private String repoPath = "";
    private boolean enabled = true;

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
