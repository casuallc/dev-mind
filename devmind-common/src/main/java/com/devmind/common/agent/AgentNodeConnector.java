package com.devmind.common.agent;

/**
 * CAP-21 节点连接 SPI（会话模块 → agent 模块：经节点 WS 长连接下发指令）。
 * 接口定义在 common（同 PlatformIntegrationHook 先例），实现方 devmind-agent 由调用方
 * 以 {@code ObjectProvider<AgentNodeConnector>} 探测注入——未装配 agent 模块时远程会话不可用。
 * 所有方法同步返回；节点离线/发送失败抛 DevMindException(CONFLICT)，由调用方转成用户可读错误。
 */
public interface AgentNodeConnector {

    /** 节点当前是否有活跃连接（ONLINE）。 */
    boolean isOnline(String nodeId);

    /**
     * 下发 launch 并阻塞等 runner ack（超时/失败/离线抛 DevMindException）。
     * 成功返回表示 runner 侧子进程已拉起。
     */
    void launch(String nodeId, AgentLaunchCommand cmd);

    /** 注入用户输入（纯文本，协议包装在 runner 侧完成）。 */
    void sendInput(String nodeId, String sessionId, String text);

    /** 授权响应。 */
    void sendAuthorize(String nodeId, String sessionId, String requestId, boolean accepted, String scope);

    /** 优雅结束（runner 关 stdin，agent 自然退出后回 exit 帧）。 */
    void sendFinish(String nodeId, String sessionId);

    /** 强杀。 */
    void sendKill(String nodeId, String sessionId);

    /** 挂起（杀进程，会话记录保留可 resume）。 */
    void sendSuspend(String nodeId, String sessionId);
}
