package com.devmind.common.agent.runtime;

import com.devmind.common.agent.SessionEvent;

/**
 * 运行时向上（能力服务层）的回调：状态变更与进程退出。
 */
public interface RuntimeListener {

    /** 状态变更（含 WAITING_AUTH 等需要"叫人"的状态）。 */
    void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent);

    /** 进程退出（自然结束/被杀）。 */
    void onExit(String sessionId, int exitCode, boolean success, String summary);

    /**
     * 一回合结束（result 事件）且带用量信息时回调，供能力层记账（会话用量账本）。
     * 默认空实现：只关心状态/退出的监听器不受影响；实现方务必快返回（在事件读取线程上）。
     */
    default void onTurnResult(String sessionId, TurnUsage usage) {
    }

    /**
     * 一回合结束（result 事件）时回调，<b>无论是否带用量</b>。
     * 供能力层做回合级后处理（CAP-84 FR-23 引用校验）；实现方务必快返回（在事件读取线程上）。
     */
    default void onResult(String sessionId, SessionEvent resultEvent) {
    }
}
