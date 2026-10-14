package com.devmind.feishubot;

import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotChannelConfigProvider;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import com.devmind.integration.service.FeishuBotGateway;
import com.devmind.integration.service.FeishuBotIdentityService;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 飞书机器人适配器（CAP-75 FR-04/05）：长连接模式收 im.message.receive_v1 事件，
 * 归一化为 {@link BotMessage} 回调底座；回复经 FeishuApiClient reply 回推。
 *
 * <p>长连接免公网 IP/域名/受信证书（webhook 路径被 194 裸 IP+自签证书堵死），SDK 封装
 * 建连鉴权与事件解密，本类不写验签/AES/重放层；断线重连由 SDK 负责（autoReconnect 默认开）。</p>
 *
 * <p>约束：SDK 事件回调要求快速返回（3s 窗口）——本类只归一化并投递，业务全在底座
 * 异步线程；无 ENABLED 飞书集成时降级禁用（告警日志），不阻断应用启动。</p>
 */
@Component
public class FeishuBotAdapter implements BotAdapter, BotChannelConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeishuBotGateway gateway;
    private final FeishuBotIdentityService identityService;

    private volatile FeishuBotGateway.FeishuBotHandle handle;
    private volatile Client client;

    public FeishuBotAdapter(FeishuBotGateway gateway, FeishuBotIdentityService identityService) {
        this.gateway = gateway;
        this.identityService = identityService;
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    public String channel() {
        return code();
    }

    @Override
    public String defaultActor() {
        FeishuBotGateway.FeishuBotHandle h = handleOrNull();
        return h == null ? null : h.defaultActor();
    }

    /** CAP-75 FR-11：飞书支持个人身份映射（open_id → user_platform_accounts IDENTITY 绑定）。 */
    @Override
    public boolean supportsIdentity() {
        return true;
    }

    @Override
    public java.util.Optional<String> resolveActor(String senderId) {
        return identityService.resolveUsername(senderId);
    }

    @Override
    public void start(BotMessageHandler handler) {
        FeishuBotGateway.FeishuBotHandle h = gateway.botHandle().orElse(null);
        if (h == null) {
            log.warn("无启用的飞书集成，飞书机器人未启动（在 平台集成 配好 FEISHU 实例后重启生效）");
            return;
        }
        this.handle = h;
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        onEvent(event, handler);
                    }
                })
                .build();
        // ws.Client.start() 内含连接/重连循环，放守护线程，不阻 SmartLifecycle 启动链
        Client c = new Client.Builder(h.appId(), h.appSecret())
                .eventHandler(dispatcher)
                .domain(h.baseUrl())
                .build();
        this.client = c;
        Thread t = new Thread(c::start, "feishu-bot-ws");
        t.setDaemon(true);
        t.start();
        log.info("飞书机器人长连接已启动（domain={}）", h.baseUrl());
    }

    @Override
    public void stop() {
        Client c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    @Override
    public void reply(BotMessage origin, String text) {
        FeishuBotGateway.FeishuBotHandle h = handleOrNull();
        if (h == null) {
            throw new IllegalStateException("飞书机器人未启用（无启用的飞书集成）");
        }
        if (origin.messageId() != null && !origin.messageId().isBlank()) {
            h.api().replyText(origin.messageId(), text);
        } else {
            // 兜底：无 reply 锚点（理论上不会来）按会话直发
            h.api().sendText(origin.externalChatId(), "chat_id", text);
        }
    }

    /**
     * 句柄优先用 start 时缓存，未启动/后配集成时惰性现取——回推链路不依赖长连接已建立
     * （集成后配/改配无需重启即可回推；长连接收事件仍需重启生效）。
     */
    private FeishuBotGateway.FeishuBotHandle handleOrNull() {
        FeishuBotGateway.FeishuBotHandle h = handle;
        return h != null ? h : gateway.botHandle().orElse(null);
    }

    // ---------------- 事件归一化 ----------------

    private void onEvent(P2MessageReceiveV1 event, BotMessageHandler handler) {
        try {
            BotMessage msg = normalize(event);
            if (msg != null) {
                handler.onMessage(msg);
            }
        } catch (Exception e) {
            // 事件线程必须兜住：异常上抛会被 SDK 吞成重推风暴
            log.error("飞书消息事件处理失败: err={}", e.getMessage(), e);
        }
    }

    /**
     * 飞书事件 → BotMessage。返回 null = 忽略（非用户发送/非文本/缺字段）。
     * 静态独立便于单测（不依赖 Spring 与网络）。
     */
    static BotMessage normalize(P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null) {
            return null;
        }
        EventSender sender = event.getEvent().getSender();
        if (sender == null || !"user".equals(sender.getSenderType())) {
            return null; // 机器人/应用自己的消息不回，防回环
        }
        EventMessage m = event.getEvent().getMessage();
        if (m == null || !"text".equals(m.getMessageType())
                || m.getChatId() == null || m.getMessageId() == null) {
            return null;
        }
        String text = extractText(m);
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean mentioned = m.getMentions() != null && m.getMentions().length > 0;
        String senderId = sender.getSenderId() == null || sender.getSenderId().getOpenId() == null
                ? "" : sender.getSenderId().getOpenId();
        String eventId = event.getHeader() == null ? null : event.getHeader().getEventId();
        return new BotMessage("feishu", m.getChatId(),
                m.getChatType() == null ? BotMessage.CHAT_TYPE_P2P : m.getChatType(),
                senderId, text, mentioned, m.getMessageId(), eventId);
    }

    /** content 是 JSON 字符串 {"text":"@_user_1 你好"}；剥离 @ 占位（mentions[].key）。 */
    static String extractText(EventMessage m) {
        String content = m.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String text;
        try {
            JsonNode node = MAPPER.readTree(content);
            text = node.path("text").asText("");
        } catch (Exception e) {
            log.warn("飞书消息 content 解析失败: {}", content);
            return null;
        }
        if (m.getMentions() != null) {
            for (MentionEvent mention : m.getMentions()) {
                if (mention.getKey() != null) {
                    text = text.replace(mention.getKey(), "");
                }
            }
        }
        return text.trim();
    }
}
