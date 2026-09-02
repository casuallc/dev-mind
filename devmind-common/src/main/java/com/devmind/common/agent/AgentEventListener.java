package com.devmind.common.agent;

import java.util.List;

/**
 * CAP-21 节点事件回传 SPI（agent 模块 → 会话模块：runner 上行帧的路由出口）。
 * 实现方 devmind-session 由 devmind-agent 以 {@code ObjectProvider<AgentEventListener>}
 * 探测注入；无实现时上行帧丢弃并记日志。
 */
public interface AgentEventListener {

    /** runner 回传的已解析事件（服务端重编 seq 后入事件流：落库 + WS 广播 + 状态机）。 */
    void onAgentEvent(String nodeId, AgentEventFrame frame);

    /** runner 侧子进程退出。ok 由服务端按既有口径计算（有 result 看 isError，否则看 exitCode）。 */
    void onAgentExit(String nodeId, String sessionId, int exitCode);

    /**
     * runner hello 对账：activeSessionIds = runner 侧仍活着的会话；
     * 服务端把不在清单内的本会话标记 FAILED（runner 重启进程已丢失）。
     */
    void onAgentHello(String nodeId, List<String> activeSessionIds);

    /** 节点连接断开（WS 关闭或心跳超时）：服务端给该节点会话打失联标记事件，不直接判 FAILED。 */
    void onAgentDisconnected(String nodeId);
}
