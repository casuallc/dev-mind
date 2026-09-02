package com.devmind.project.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * devmind.project.* — MVP 阶段项目不做表，yml 预置一个仓库即可试用（CAP-02 落地后接入表）。
 */
@ConfigurationProperties(prefix = "devmind.project")
public class ProjectProperties {

    /** 预置项目：本地 git 仓库绝对路径 */
    private String defaultPath = "";
    /** 预置项目名称 */
    private String defaultName = "default";
    /** 预置项目标签（知识注入按标签粗略过滤） */
    private List<String> defaultTags = new ArrayList<>();
    /** 项目并发写默认上限（FR-09，锁未单独配置时生效） */
    private int defaultMaxConcurrent = 1;
    /** CAP-23 克隆工作区根目录（相对启动目录）：仓库克隆到 <workspaceRoot>/<projectId>/<repo子目录> */
    private String workspaceRoot = "data/repositories";

    public String getDefaultPath() { return defaultPath; }
    public void setDefaultPath(String defaultPath) { this.defaultPath = defaultPath; }
    public String getDefaultName() { return defaultName; }
    public void setDefaultName(String defaultName) { this.defaultName = defaultName; }
    public List<String> getDefaultTags() { return defaultTags; }
    public void setDefaultTags(List<String> defaultTags) { this.defaultTags = defaultTags; }
    public int getDefaultMaxConcurrent() { return defaultMaxConcurrent; }
    public void setDefaultMaxConcurrent(int defaultMaxConcurrent) { this.defaultMaxConcurrent = defaultMaxConcurrent; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
}
