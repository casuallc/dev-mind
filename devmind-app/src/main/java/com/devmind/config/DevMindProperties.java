package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台全局配置（application.yml 的 devmind.*）。
 */
@ConfigurationProperties(prefix = "devmind")
public class DevMindProperties {

    /** 会话管理（CAP-05） */
    private Session session = new Session();
    /** worktree 管理 */
    private Worktree worktree = new Worktree();
    /** 知识库（CAP-04 预留，MVP 用目录注入） */
    private Knowledge knowledge = new Knowledge();
    /** 项目（MVP 简化：先预置一个项目，CAP-02 落地后接入表） */
    private Project project = new Project();

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
    public Worktree getWorktree() { return worktree; }
    public void setWorktree(Worktree worktree) { this.worktree = worktree; }
    public Knowledge getKnowledge() { return knowledge; }
    public void setKnowledge(Knowledge knowledge) { this.knowledge = knowledge; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public static class Session {
        /** claude 可执行文件路径；空 = 自动探测(where claude) */
        private String claudePath = "";
        /** 模型；空 = CLI 默认 */
        private String model = "";
        /** 默认权限模式：acceptEdits=放手 / bypassPermissions=全放开 / plan 等 */
        private String permissionMode = "acceptEdits";
        /** WAITING_* 超时（秒），超时触发提示 */
        private int inputTimeout = 300;
        /** 空闲超时（秒），0=不自动挂起 */
        private int idleTimeout = 0;
        /** 内存回放缓冲条数 */
        private int ringBuffer = 1000;
        /** 事件批量落库周期（毫秒） */
        private int eventFlushMs = 200;
        /** 最大并发会话数，超出排队 */
        private int maxConcurrent = 4;
        /** 单条事件内容截断字节数，防前端卡死 */
        private int maxEventBytes = 100 * 1024;

        public String getClaudePath() { return claudePath; }
        public void setClaudePath(String claudePath) { this.claudePath = claudePath; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
        public int getInputTimeout() { return inputTimeout; }
        public void setInputTimeout(int inputTimeout) { this.inputTimeout = inputTimeout; }
        public int getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(int idleTimeout) { this.idleTimeout = idleTimeout; }
        public int getRingBuffer() { return ringBuffer; }
        public void setRingBuffer(int ringBuffer) { this.ringBuffer = ringBuffer; }
        public int getEventFlushMs() { return eventFlushMs; }
        public void setEventFlushMs(int eventFlushMs) { this.eventFlushMs = eventFlushMs; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getMaxEventBytes() { return maxEventBytes; }
        public void setMaxEventBytes(int maxEventBytes) { this.maxEventBytes = maxEventBytes; }
    }

    public static class Worktree {
        /** worktree 根目录；空 = 项目内 .devmind/worktrees */
        private String root = "";
        /** 会话分支基准分支 */
        private String baseBranch = "master";

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getBaseBranch() { return baseBranch; }
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    }

    public static class Knowledge {
        /** knowledge-repo 本地路径（LocalDirInjector 使用） */
        private String repoPath = "";
        private boolean enabled = true;

        public String getRepoPath() { return repoPath; }
        public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Project {
        /** MVP 预置项目：本地仓库绝对路径 */
        private String defaultPath = "";
        /** MVP 预置项目：名称 */
        private String defaultName = "default";
        /** MVP 预置项目：标签 */
        private List<String> defaultTags = new ArrayList<>();

        public String getDefaultPath() { return defaultPath; }
        public void setDefaultPath(String defaultPath) { this.defaultPath = defaultPath; }
        public String getDefaultName() { return defaultName; }
        public void setDefaultName(String defaultName) { this.defaultName = defaultName; }
        public List<String> getDefaultTags() { return defaultTags; }
        public void setDefaultTags(List<String> defaultTags) { this.defaultTags = defaultTags; }
    }
}
