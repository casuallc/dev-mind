package com.devmind.session.config;

import com.devmind.common.agent.runtime.RuntimeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * devmind.session.* — 会话执行配置。
 * CAP-34 FR-02：服务端零执行，executor/claude-path 已删除（claude 二进制解析在 runner 侧
 * agent.properties）；model/permissionMode 保留，作为 launch 帧下发默认值。
 */
@ConfigurationProperties(prefix = "devmind.session")
public class SessionProperties {

    /** 模型；空 = CLI 默认 */
    private String model = "";
    /** 默认权限模式：acceptEdits=放手 / bypassPermissions=全放开 / plan 等 */
    private String permissionMode = "acceptEdits";
    /** WAITING_* 超时（秒），超时触发提示（预留通知） */
    private int inputTimeout = 300;
    /** 空闲超时（秒），0=不自动挂起 */
    private int idleTimeout = 0;
    /** 内存回放缓冲条数 */
    private int ringBuffer = 1000;
    /** 事件批量落库周期（毫秒） */
    private int eventFlushMs = 200;
    /** 最大并发会话数，超出排队/拒绝 */
    private int maxConcurrent = 4;
    /** 单条事件内容截断字节数，防前端卡死 */
    private int maxEventBytes = 100 * 1024;
    /** CAP-28 one-shot 总结会话专用权限模式（只读任务，不复用全局 acceptEdits） */
    private String oneshotPermissionMode = "plan";

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
    public String getOneshotPermissionMode() { return oneshotPermissionMode; }
    public void setOneshotPermissionMode(String oneshotPermissionMode) { this.oneshotPermissionMode = oneshotPermissionMode; }

    /** CAP-30：转换为内核运行时参数（common.agent.runtime 与 Spring 配置解耦的桥梁）。
     *  CAP-34：服务端不再拉起进程，claudePath 恒空（仅 runner 侧解析）。 */
    public RuntimeSettings toRuntimeSettings() {
        return new RuntimeSettings(ringBuffer, idleTimeout, maxEventBytes, permissionMode, "");
    }
}
