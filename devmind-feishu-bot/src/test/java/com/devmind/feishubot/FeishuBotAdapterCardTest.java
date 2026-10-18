package com.devmind.feishubot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devmind.bot.spi.BotCardAction;
import com.devmind.common.agent.gateway.AgentCard;
import com.devmind.common.agent.gateway.AgentCardButton;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.model.Header;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CAP-76 FR-23/24：中性卡片 → 飞书卡片 JSON，卡片回调 → BotCardAction。 */
class FeishuBotAdapterCardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rendersConfirmAndCancelButtons() throws Exception {
        AgentCard card = new AgentCard("需要确认", AgentCard.WARNING, "**创建需求**\n- 标题：登录",
                List.of(AgentCardButton.confirm("确认 A7K2", "action-1"),
                        AgentCardButton.cancel("取消", "action-1")),
                "15 分钟内有效");

        JsonNode root = MAPPER.readTree(FeishuBotAdapter.renderCard(card));

        assertEquals("orange", root.path("header").path("template").asText());
        assertEquals("需要确认", root.path("header").path("title").path("content").asText());
        assertTrue(root.path("config").path("update_multi").asBoolean());

        JsonNode elements = root.path("elements");
        assertEquals("markdown", elements.get(0).path("tag").asText());
        assertTrue(elements.get(0).path("content").asText().contains("创建需求"));

        JsonNode actions = elements.get(1).path("actions");
        JsonNode confirm = actions.get(0);
        assertEquals("primary", confirm.path("type").asText());
        assertEquals("copilot_confirm", confirm.path("value").path("devmind").asText());
        assertEquals("action-1", confirm.path("value").path("id").asText());
        JsonNode cancel = actions.get(1);
        assertEquals("default", cancel.path("type").asText());
        assertEquals("copilot_cancel", cancel.path("value").path("devmind").asText());

        assertEquals("note", elements.get(2).path("tag").asText());
        assertEquals("15 分钟内有效",
                elements.get(2).path("elements").get(0).path("content").asText());
    }

    @Test
    void rendersLinkButtonAndTemplateMapping() throws Exception {
        AgentCard card = new AgentCard("结果", AgentCard.DANGER, "失败", List.of(
                AgentCardButton.link("查看日志", "https://devmind/build/1")), null);
        JsonNode root = MAPPER.readTree(FeishuBotAdapter.renderCard(card));

        assertEquals("red", root.path("header").path("template").asText());
        JsonNode button = root.path("elements").get(1).path("actions").get(0);
        assertEquals("https://devmind/build/1", button.path("url").asText());
        assertFalse(button.has("value"));
    }

    @Test
    void mapsSuccessTemplateToGreen() throws Exception {
        JsonNode root = MAPPER.readTree(FeishuBotAdapter.renderCard(
                AgentCard.of("完成", AgentCard.SUCCESS, "ok")));
        assertEquals("green", root.path("header").path("template").asText());
        assertEquals(1, root.path("elements").size());
    }

    @Test
    void normalizesCardActionFromEvent() {
        CallBackAction action = new CallBackAction();
        action.setValue(Map.of("devmind", "copilot_confirm", "id", "action-1"));
        CallBackContext context = new CallBackContext();
        context.setOpenChatId("oc_chat");
        context.setOpenMessageId("om_card");
        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId("ou_user");
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        data.setAction(action);
        data.setContext(context);
        data.setOperator(operator);
        Header header = new Header();
        header.setEventId("evt_1");
        P2CardActionTrigger event = new P2CardActionTrigger();
        event.setEvent(data);
        event.setHeader(header);

        BotCardAction out = FeishuBotAdapter.normalizeCardAction(event);

        assertEquals("feishu", out.channel());
        assertEquals("oc_chat", out.externalChatId());
        assertEquals("ou_user", out.senderId());
        assertEquals("om_card", out.messageId());
        assertEquals("evt_1", out.eventId());
        assertEquals("action-1", out.value().get("id"));
        assertNull(out.chatType());
    }

    @Test
    void ignoresMalformedCardActions() {
        assertNull(FeishuBotAdapter.normalizeCardAction(null));
        assertNull(FeishuBotAdapter.normalizeCardAction(new P2CardActionTrigger()));

        P2CardActionTriggerData data = new P2CardActionTriggerData();
        CallBackAction action = new CallBackAction();
        action.setValue(Map.of("devmind", "copilot_confirm"));
        data.setAction(action);
        P2CardActionTrigger event = new P2CardActionTrigger();
        event.setEvent(data);
        assertNull(FeishuBotAdapter.normalizeCardAction(event)); // 缺 chat
    }
}
