package com.devmind.common.agent.runtime;

import com.devmind.common.agent.InputImage;
import com.devmind.common.agent.SessionEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 会话运行时句柄：能力服务层（session/chat）面向本接口，屏蔽本地子进程（{@link SessionRuntime}）
 * 与远程节点会话（{@link RemoteSessionRuntime}，CAP-21）的差异。
 */
public interface SessionHandle extends AutoCloseable {

    String id();

    SessionState state();

    long currentSeq();

    /** 注入用户消息。 */
    void injectInput(String text);

    /** CAP-32：注入用户消息（可带图片附件，附件引用记入 user 事件 payload）。 */
    void injectInput(String text, List<InputImage> images);

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
