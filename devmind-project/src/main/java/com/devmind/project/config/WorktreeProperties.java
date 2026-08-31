package com.devmind.project.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.worktree.* — worktree 根目录与基准分支。
 */
@ConfigurationProperties(prefix = "devmind.worktree")
public class WorktreeProperties {

    /** worktree 根目录；空 = 项目内 .devmind/worktrees */
    private String root = "";
    /** 会话分支基准分支 */
    private String baseBranch = "master";

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    public String getBaseBranch() { return baseBranch; }
    public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
}
