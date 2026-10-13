package com.devmind.feishubot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.bot.spi.BotMessage;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

/** CAP-75 FR-05：飞书事件 JSON → BotMessage 归一化（@机器人/私聊/非文本忽略/防回环）。 */
class FeishuBotAdapterNormalizeTest {

    private static P2MessageReceiveV1 event(String senderType, String msgType, String content,
                                            String chatType, MentionEvent[] mentions) {
        EventSender sender = new EventSender();
        sender.setSenderType(senderType);
        UserId uid = new UserId();
        uid.setOpenId("ou_abc");
        sender.setSenderId(uid);

        EventMessage m = new EventMessage();
        m.setMessageId("om_123");
        m.setChatId("oc_456");
        m.setChatType(chatType);
        m.setMessageType(msgType);
        m.setContent(content);
        m.setMentions(mentions);

        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setSender(sender);
        data.setMessage(m);

        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setEvent(data);
        Header header = new Header();
        header.setEventId("evt-1");
        event.setHeader(header);
        return event;
    }

    @Test
    void p2pTextNormalized() {
        BotMessage msg = FeishuBotAdapter.normalize(
                event("user", "text", "{\"text\":\"你好\"}", "p2p", null));
        assertEquals("feishu", msg.channel());
        assertEquals("oc_456", msg.externalChatId());
        assertEquals("p2p", msg.chatType());
        assertEquals("ou_abc", msg.senderId());
        assertEquals("你好", msg.text());
        assertFalse(msg.mentioned());
        assertEquals("om_123", msg.messageId());
        assertEquals("evt-1", msg.eventId());
    }

    @Test
    void groupMentionStripped() {
        MentionEvent mention = new MentionEvent();
        mention.setKey("@_user_1");
        mention.setName("devmind-bot");
        BotMessage msg = FeishuBotAdapter.normalize(event("user", "text",
                "{\"text\":\"@_user_1 /build\"}", "group", new MentionEvent[]{mention}));
        assertEquals("/build", msg.text());
        assertTrue(msg.mentioned());
        assertEquals("group", msg.chatType());
    }

    @Test
    void nonUserSenderIgnored() {
        assertNull(FeishuBotAdapter.normalize(
                event("app", "text", "{\"text\":\"hi\"}", "group", null)));
        assertNull(FeishuBotAdapter.normalize(
                event("anonymous", "text", "{\"text\":\"hi\"}", "p2p", null)));
    }

    @Test
    void nonTextIgnored() {
        assertNull(FeishuBotAdapter.normalize(
                event("user", "image", "{\"image_key\":\"img\"}", "p2p", null)));
        assertNull(FeishuBotAdapter.normalize(
                event("user", "post", "{}", "p2p", null)));
    }

    @Test
    void blankTextIgnored() {
        assertNull(FeishuBotAdapter.normalize(
                event("user", "text", "{\"text\":\"  \"}", "p2p", null)));
        assertNull(FeishuBotAdapter.normalize(
                event("user", "text", "not-json", "p2p", null)));
    }
}
