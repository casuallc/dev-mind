package com.devmind.chat.config;

import com.devmind.common.agent.runtime.RuntimeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.chat.* — CAP-30 通用问答配置。主类 @ConfigurationPropertiesScan 全局扫描，无需注册。
 * CAP-34 FR-02：服务端零执行，executor/claude-path/work-dir 已删除（执行与沙箱均在 runner 侧）。
 */
@ConfigurationProperties(prefix = "devmind.chat")
public class ChatProperties {

    /** 模型；空 = CLI 默认 */
    private String model = "";
    /** 默认权限模式：acceptEdits=放手 / bypassPermissions=全放开 / plan 等 */
    private String permissionMode = "acceptEdits";
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

    // ---- CAP-49 模型执行体（服务端直连已接入的 CHAT 端点） ----

    /**
     * 最大并发<b>生成中</b>的模型问答数（与 Agent 分账：空闲的模型问答不占额度）。
     * 模型会话空闲时不占任何外部资源，不该挤占 runner 配额。
     */
    private int maxConcurrentModel = 8;
    /** 流式增量合并窗口（毫秒）：攒够时间或字数才发一条 text_delta */
    private int streamFlushMs = 120;
    /** 流式增量合并字数阈值（与窗口先到先发） */
    private int streamFlushChars = 24;
    /** 单轮回答上限（字符）：超出截断并在 result 里标 truncated */
    private int answerMaxChars = 100_000;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
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
    public int getMaxConcurrentModel() { return maxConcurrentModel; }
    public void setMaxConcurrentModel(int maxConcurrentModel) { this.maxConcurrentModel = maxConcurrentModel; }
    public int getStreamFlushMs() { return streamFlushMs; }
    public void setStreamFlushMs(int streamFlushMs) { this.streamFlushMs = streamFlushMs; }
    public int getStreamFlushChars() { return streamFlushChars; }
    public void setStreamFlushChars(int streamFlushChars) { this.streamFlushChars = streamFlushChars; }
    public int getAnswerMaxChars() { return answerMaxChars; }
    public void setAnswerMaxChars(int answerMaxChars) { this.answerMaxChars = answerMaxChars; }

    /** 转换为内核运行时参数（common.agent.runtime 与 Spring 配置解耦的桥梁）。
     *  CAP-34：服务端不再拉起进程，claudePath 恒空（仅 runner 侧解析）。 */
    public RuntimeSettings toRuntimeSettings() {
        return new RuntimeSettings(ringBuffer, idleTimeout, maxEventBytes, permissionMode, "");
    }
}
