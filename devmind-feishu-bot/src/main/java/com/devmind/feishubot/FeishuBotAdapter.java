package com.devmind.feishubot;

import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotCardAction;
import com.devmind.bot.spi.BotChannelConfigProvider;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import com.devmind.common.agent.gateway.AgentCard;
import com.devmind.common.agent.gateway.AgentCardButton;
import com.devmind.integration.service.FeishuBotGateway;
import com.devmind.integration.service.FeishuBotIdentityService;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
                // CAP-76 FR-24：卡片按钮回调（需在开放平台订阅 card.action.trigger 并重新发布版本）
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                        onCardActionEvent(event, handler);
                        return new P2CardActionTriggerResponse();
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
     * CAP-75 FR-20：无入站消息 origin 的主动推送（会话终态进展）——按 chat_id 直发。
     * 失败抛异常，由监听器记日志（订阅在推送前已消费，不补推）。
     */
    @Override
    public void send(String externalChatId, String text) {
        FeishuBotGateway.FeishuBotHandle h = handleOrNull();
        if (h == null) {
            throw new IllegalStateException("飞书机器人未启用（无启用的飞书集成）");
        }
        h.api().sendText(externalChatId, "chat_id", text);
    }

    /**
     * 句柄优先用 start 时缓存，未启动/后配集成时惰性现取——回推链路不依赖长连接已建立
     * （集成后配/改配无需重启即可回推；长连接收事件仍需重启生效）。
     */
    private FeishuBotGateway.FeishuBotHandle handleOrNull() {
        FeishuBotGateway.FeishuBotHandle h = handle;
        return h != null ? h : gateway.botHandle().orElse(null);
    }

    /** CAP-75 FR-16：出站图片——先上传拿 image_key 再按消息/会话回推。 */
    @Override
    public void replyImage(BotMessage origin, String fileName, String contentType, byte[] bytes) {
        FeishuBotGateway.FeishuBotHandle h = handleOrNull();
        if (h == null) {
            throw new IllegalStateException("飞书机器人未启用（无启用的飞书集成）");
        }
        String imageKey = h.api().uploadImage(bytes, fileName);
        if (origin.messageId() != null && !origin.messageId().isBlank()) {
            h.api().replyImage(origin.messageId(), imageKey);
        } else {
            h.api().sendImage(origin.externalChatId(), "chat_id", imageKey);
        }
    }

    /** CAP-75 FR-17：入站图片——按 image_key 拉字节（底座在 botExecutor 调用，不占事件窗口）。 */
    @Override
    public byte[] downloadImage(String imageKey) {
        return requireHandle().api().downloadImage(imageKey);
    }

    // ---------------- CAP-76 FR-23：交互卡片 ----------------

    @Override
    public boolean supportsCard() {
        return true;
    }

    @Override
    public String replyCard(BotMessage origin, AgentCard card) {
        String json = renderCard(card);
        if (origin.messageId() != null && !origin.messageId().isBlank()) {
            return requireHandle().api().replyCard(origin.messageId(), json);
        }
        return requireHandle().api().sendCard(origin.externalChatId(), "chat_id", json);
    }

    @Override
    public void updateCard(String messageId, AgentCard card) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("更新卡片缺少消息 id");
        }
        requireHandle().api().updateCard(messageId, renderCard(card));
    }

    @Override
    public void sendCard(String externalChatId, AgentCard card) {
        requireHandle().api().sendCard(externalChatId, "chat_id", renderCard(card));
    }

    private FeishuBotGateway.FeishuBotHandle requireHandle() {
        FeishuBotGateway.FeishuBotHandle h = handleOrNull();
        if (h == null) {
            throw new IllegalStateException("飞书机器人未启用（无启用的飞书集成）");
        }
        return h;
    }

    // ---------------- CAP-76 卡片渲染与回调归一化 ----------------

    /** 中性卡片 → 飞书 interactive 卡片 JSON（2.0 之前 message card 结构，PATCH 可更新）。 */
    static String renderCard(AgentCard card) {
        ObjectNode root = MAPPER.createObjectNode();
        // update_multi=true 是 PATCH /im/v1/messages/{id} 原地更新的前提
        ObjectNode config = root.putObject("config");
        config.put("wide_screen_mode", true);
        config.put("update_multi", true);
        ObjectNode header = root.putObject("header");
        header.put("template", templateOf(card.template()));
        ObjectNode title = header.putObject("title");
        title.put("tag", "plain_text");
        title.put("content", card.title() == null ? "" : card.title());
        ArrayNode elements = root.putArray("elements");
        if (!card.markdown().isBlank()) {
            ObjectNode md = elements.addObject();
            md.put("tag", "markdown");
            md.put("content", card.markdown());
        }
        if (!card.buttons().isEmpty()) {
            ObjectNode action = elements.addObject();
            action.put("tag", "action");
            ArrayNode actions = action.putArray("actions");
            for (AgentCardButton b : card.buttons()) {
                ObjectNode btn = actions.addObject();
                btn.put("tag", "button");
                ObjectNode text = btn.putObject("text");
                text.put("tag", "plain_text");
                text.put("content", b.label() == null || b.label().isBlank() ? "查看" : b.label());
                if (b.isLink()) {
                    btn.put("type", "default");
                    btn.put("url", b.url() == null ? "" : b.url());
                } else {
                    btn.put("type", AgentCardButton.CANCEL.equals(b.operation())
                            ? "default" : "primary");
                    // value 只带操作与确认单 id；身份/会话一律以回调事件为准（FR-24）
                    ObjectNode value = btn.putObject("value");
                    value.put("devmind", AgentCardButton.CONFIRM.equals(b.operation())
                            ? "copilot_confirm" : "copilot_cancel");
                    value.put("id", b.actionId() == null ? "" : b.actionId());
                }
            }
        }
        if (card.footer() != null && !card.footer().isBlank()) {
            ObjectNode note = elements.addObject();
            note.put("tag", "note");
            ObjectNode txt = note.putArray("elements").addObject();
            txt.put("tag", "plain_text");
            txt.put("content", card.footer());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String templateOf(String template) {
        if (AgentCard.SUCCESS.equals(template)) {
            return "green";
        }
        if (AgentCard.WARNING.equals(template)) {
            return "orange";
        }
        if (AgentCard.DANGER.equals(template)) {
            return "red";
        }
        return "blue";
    }

    /** 卡片回调入口（SDK 事件线程）：归一化后投递底座，异常必须兜住防 SDK 重推风暴。 */
    private void onCardActionEvent(P2CardActionTrigger event, BotMessageHandler handler) {
        try {
            BotCardAction action = normalizeCardAction(event);
            if (action != null) {
                handler.onCardAction(action);
            }
        } catch (Exception e) {
            log.error("飞书卡片回调处理失败: err={}", e.getMessage(), e);
        }
    }

    /**
     * 飞书 card.action.trigger → BotCardAction。返回 null = 忽略（缺会话/动作/value）。
     * 回调里没有 chat_type，chatType 置空（绑定已在消息阶段建立）。
     */
    static BotCardAction normalizeCardAction(P2CardActionTrigger event) {
        if (event == null || event.getEvent() == null) {
            return null;
        }
        P2CardActionTriggerData data = event.getEvent();
        CallBackAction action = data.getAction();
        if (action == null || action.getValue() == null) {
            return null;
        }
        CallBackContext ctx = data.getContext();
        if (ctx == null || ctx.getOpenChatId() == null || ctx.getOpenChatId().isBlank()) {
            return null;
        }
        CallBackOperator operator = data.getOperator();
        String senderId = operator == null || operator.getOpenId() == null
                ? "" : operator.getOpenId();
        String eventId = event.getHeader() == null ? null : event.getHeader().getEventId();
        return new BotCardAction("feishu", ctx.getOpenChatId(), null, senderId,
                ctx.getOpenMessageId(), eventId, action.getValue());
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
     * 飞书事件 → BotMessage。返回 null = 忽略（非用户发送/非文本非图片/缺字段）。
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
        if (m == null || m.getChatId() == null || m.getMessageId() == null) {
            return null;
        }
        // FR-17：text 抽正文；image 只抽 image_key（下载留给底座 botExecutor，不占 3s 窗口）
        String text = null;
        String imageKey = null;
        if ("text".equals(m.getMessageType())) {
            text = extractText(m);
            if (text == null || text.isBlank()) {
                return null;
            }
        } else if ("image".equals(m.getMessageType())) {
            imageKey = extractImageKey(m);
            if (imageKey == null || imageKey.isBlank()) {
                return null;
            }
        } else {
            return null;
        }
        boolean mentioned = m.getMentions() != null && m.getMentions().length > 0;
        String senderId = sender.getSenderId() == null || sender.getSenderId().getOpenId() == null
                ? "" : sender.getSenderId().getOpenId();
        String eventId = event.getHeader() == null ? null : event.getHeader().getEventId();
        return new BotMessage("feishu", m.getChatId(),
                m.getChatType() == null ? BotMessage.CHAT_TYPE_P2P : m.getChatType(),
                senderId, text, mentioned, m.getMessageId(), eventId, imageKey);
    }

    /** 图片消息 content {"image_key":"img_v3_xxx"} 抽键。 */
    static String extractImageKey(EventMessage m) {
        String content = m.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(content).path("image_key").asText("");
        } catch (Exception e) {
            log.warn("飞书图片消息 content 解析失败: {}", content);
            return null;
        }
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
