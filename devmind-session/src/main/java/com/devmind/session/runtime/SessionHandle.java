package com.devmind.session.runtime;

import com.devmind.session.model.SessionEvent;
import com.devmind.session.model.SessionState;

import java.util.List;
import java.util.function.Consumer;

/**
 * 会话运行时句柄：SessionManagerService 面向本接口，屏蔽本地子进程（{@link SessionRuntime}）
 * 与远程节点会话（{@link RemoteSessionRuntime}，CAP-21）的差异。
 */
public interface SessionHandle extends AutoCloseable {

    String id();

    SessionState state();

    long currentSeq();

    /** 注入用户消息。 */
    void injectInput(String text);

    /** 授权响应。 */
    void authorize(String requestId, boolean accepted, String scope);

    /** 优雅结束（关 stdin，agent 自然退出 → DONE/FAILED）。 */
    void finish();

    /** 强杀。 */
    void kill();

    /** 挂起（杀进程保记录，可 resume）。 */
    void suspend();

    /** 订阅实时事件流，返回历史回放（环形缓冲快照）。 */
    List<SessionEvent> subscribe(Consumer<SessionEvent> consumer);

    void unsubscribe(Consumer<SessionEvent> consumer);

    void unsubscribeAll();

    List<SessionEvent> replay();

    @Override
    void close();
}
