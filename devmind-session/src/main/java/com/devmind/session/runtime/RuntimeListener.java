package com.devmind.session.runtime;

import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionState;

/**
 * SessionRuntime 向上（服务层）的回调：状态变更与进程退出。
 */
public interface RuntimeListener {

    /** 状态变更（含 WAITING_AUTH 等需要"叫人"的状态）。 */
    void onStateChange(String sessionId, SessionState state, SessionEvent stateEvent);

    /** 进程退出（自然结束/被杀）。 */
    void onExit(String sessionId, int exitCode, boolean success, String summary);
}
