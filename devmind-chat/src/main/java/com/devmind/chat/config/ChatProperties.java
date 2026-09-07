package com.devmind.chat.config;

import com.devmind.common.agent.runtime.RuntimeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.chat.* — CAP-30 通用问答配置。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 */
@ConfigurationProperties(prefix = "devmind.chat")
public class ChatProperties {

    /** 执行器：fake=内置假进程(自测/无 claude 环境) / claude=真实 Claude Code CLI */
    private String executor = "fake";
    /** claude 可执行文件路径；空 = 自动探测(where/which claude) */
    private String claudePath = "";
    /** 模型；空 = CLI 默认 */
    private String model = "";
    /** 默认权限模式：acceptEdits=放手 / bypassPermissions=全放开 / plan 等 */
    private String permissionMode = "acceptEdits";
    /** 本地问答沙箱根目录（每个问答一个 <workDir>/<chatId> 空目录）；空 = ${user.dir}/data/chats */
    private String workDir = "";
    /** 空闲超时（秒），0=不自动结束 */
    private int idleTimeout = 0;
    /** 内存回放缓冲条数 */
    private int ringBuffer = 1000;
    /** 事件批量落库周期（毫秒） */
    private int eventFlushMs = 200;
    /** 最大并发问答数 */
    private int maxConcurrent = 4;
    /** 单条事件内容截断字节数 */
    private int maxEventBytes = 100 * 1024;

    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }
    public String getClaudePath() { return claudePath; }
    public void setClaudePath(String claudePath) { this.claudePath = claudePath; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
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

    /** 转换为内核运行时参数（common.agent.runtime 与 Spring 配置解耦的桥梁）。 */
    public RuntimeSettings toRuntimeSettings() {
        return new RuntimeSettings(ringBuffer, idleTimeout, maxEventBytes, permissionMode, claudePath);
    }
}
