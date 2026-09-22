package com.devmind.common.agent;

import java.util.Map;

/**
 * CAP-54 工作区状态上行 SPI（agent 模块 → 会话模块：runner `workspace_status` 帧的路由出口）。
 *
 * <p>与 {@link AgentEventListener} 并列但<b>独立于会话事件流</b>：git 状态是「最新值覆盖」
 * 语义的瞬态视图，不进环形缓冲回放、不落库。实现方 devmind-session / devmind-chat 由
 * devmind-agent 以 {@code ObjectProvider<WorkspaceStatusListener>} 探测广播，
 * 各实现按自己是否持有该 sessionId 自行忽略未命中帧（同既有 bridge 模式）。</p>
 */
public interface WorkspaceStatusListener {

    /**
     * runner 上报的工作区 git 变更快照（结构见 CAP-54 FR-01：gitAvailable/repos[]/total/ts，
     * agent 模块不解析内容，原样透传 Map）。
     */
    void onWorkspaceStatus(String nodeId, String sessionId, Map<String, Object> snapshot);
}
