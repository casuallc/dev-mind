package com.devmind.chat.controller;

import com.devmind.chat.service.ChatManagerService;
import com.devmind.common.agent.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-30 问答实时流：WS /ws/chats/{id}，帧协议与 /ws/sessions/{id} 完全一致
 * （snapshot/event/error/pong 下行 + input/authorize/interrupt/ping 上行），前端共享同一套流组件。
 * CAP-49 起下行多一个非致命 {@code notice}：上行动作被拒（问答已删、模型问答不支持该动作…）
 * 只提示这一次没成，不能走 {@code error}——前端把 {@code error} 当致命处理（关连接、不再重连），
 * 那样一次动作失败会把整条实时流打断。
 *
 * <p>CAP-49：模型执行体会在一轮里推上千条 {@code text_delta}，慢客户端会把事件推送线程
 * （进而把上游 SSE 读取）钉死，故由 {@link com.devmind.chat.config.ChatWsConfig} 给会话套上
 * {@code ConcurrentWebSocketSessionDecorator}（发送缓冲 + 超时踢连接）。</p>
 */
@Component
public class ChatWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWsHandler.class);
    private static final String ATTR_CONSUMER = "consumer";
    /** CAP-54：workspace 快照订阅消费者（与事件消费者分开注销） */
    private static final String ATTR_WS_CONSUMER = "wsConsumer";

    private final ChatManagerService service;
    private final ObjectMapper mapper;

    public ChatWsHandler(ChatManagerService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = extractChatId(session);
        if (id == null) {
            send(session, Map.of("type", "error", "message", "URL 缺少问答 ID"));
            closeQuietly(session);
            return;
        }
        try {
            Consumer<SessionEvent> consumer = ev -> send(session,
                    Map.of("type", "event", "seq", ev.seq(), "event", ev));
            List<SessionEvent> replay = service.subscribe(id, consumer);
            session.getAttributes().put(ATTR_CONSUMER, consumer);
            send(session, snapshot(id, replay));
            // CAP-54：订阅工作区快照旁路 + 补发当前缓存（老 runner 无缓存则什么都不发）
            Consumer<Map<String, Object>> wsConsumer = snap -> {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("type", "workspace");
                frame.put("snapshot", snap);
                send(session, frame);
            };
            service.subscribeWorkspace(id, wsConsumer);
            session.getAttributes().put(ATTR_WS_CONSUMER, wsConsumer);
            Map<String, Object> cached = service.latestWorkspaceSnapshot(id);
            if (cached != null) {
                wsConsumer.accept(cached);
            }
        } catch (Exception e) {
            log.warn("订阅问答失败: {} err={}", id, e.getMessage());
            send(session, Map.of("type", "error", "message", "问答不可用: " + e.getMessage()));
            closeQuietly(session);
        }
    }

    private Map<String, Object> snapshot(String id, List<SessionEvent> replay) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "snapshot");
        m.put("sessionId", id);
        m.put("seq", replay.isEmpty() ? 0 : replay.get(replay.size() - 1).seq());
        m.put("events", replay);
        return m;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        String id = extractChatId(session);
        if (id == null) {
            return;
        }
        // 上行动作失败（问答已删、模型问答不支持该动作…）只回 notice 帧，不能把整条连接掀掉：
        // 事件流断了用户就看不到后续回答，比"这一个动作没成"严重得多。
        // 必须是 notice 而非 error——前端对 error 的处理是"关连接 + 不再重连"（那是对
        // "会话已无运行时"的语义），拿它回动作失败等于一次误触就把实时流打死。
        try {
            switch (type) {
                case "input" -> service.input(id, node.path("text").asText(""), parseImages(node));
                case "authorize" -> service.authorize(id,
                        node.path("accepted").asBoolean(false),
                        node.path("scope").asText("once"),
                        node.path("requestId").asText(""));
                // CAP-49「停止生成」：模型执行体中断在跑的那一轮
                case "interrupt" -> service.interrupt(id);
                case "ping" -> send(session, Map.of("type", "pong"));
                default -> { }
            }
        } catch (Exception e) {
            log.debug("WS 上行动作失败: chat={} type={} err={}", id, type, e.getMessage());
            send(session, Map.of("type", "notice", "message", "操作未生效: " + e.getMessage()));
        }
    }

    /** CAP-32：input 帧 images 附件引用数组 → ImageRef（只传引用，base64 由服务端经附件 SPI 解析）。 */
    private List<com.devmind.chat.dto.ImageRef> parseImages(JsonNode node) {
        JsonNode images = node.path("images");
        if (!images.isArray() || images.isEmpty()) {
            return List.of();
        }
        List<com.devmind.chat.dto.ImageRef> out = new java.util.ArrayList<>();
        for (JsonNode img : images) {
            String attachmentId = img.path("attachmentId").asText("");
            if (!attachmentId.isBlank()) {
                out.add(new com.devmind.chat.dto.ImageRef(attachmentId,
                        img.path("name").asText(null), img.path("contentType").asText(null)));
            }
        }
        return out;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = extractChatId(session);
        Object consumer = session.getAttributes().remove(ATTR_CONSUMER);
        if (id != null && consumer instanceof Consumer<?> c) {
            @SuppressWarnings("unchecked")
            Consumer<SessionEvent> cast = (Consumer<SessionEvent>) c;
            service.unsubscribe(id, cast);
        }
        Object wsConsumer = session.getAttributes().remove(ATTR_WS_CONSUMER);
        if (id != null && wsConsumer instanceof Consumer<?> c) {
            @SuppressWarnings("unchecked")
            Consumer<Map<String, Object>> cast = (Consumer<Map<String, Object>>) c;
            service.unsubscribeWorkspace(id, cast);
        }
    }

    private String extractChatId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : null;
    }

    private void send(WebSocketSession session, Object payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.debug("WS 发送失败(可能已关闭): {}", e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            // 忽略
        }
    }
}
