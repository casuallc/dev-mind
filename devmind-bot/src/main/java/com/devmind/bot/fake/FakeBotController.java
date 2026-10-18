package com.devmind.bot.fake;

import com.devmind.bot.spi.BotCardAction;
import com.devmind.bot.spi.BotMessage;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E 驱动端点（仅 e2e profile）：注入 fake 入站消息 / 拉取已记录回复。
 * 走 /api/** 既有认证，不额外开口子。
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/e2e/bot")
public class FakeBotController {

    private final FakeBotAdapter adapter;

    public FakeBotController(FakeBotAdapter adapter) {
        this.adapter = adapter;
    }

    public record Incoming(String channel, String externalChatId, String chatType, String senderId,
                           String text, Boolean mentioned, String messageId, String eventId,
                           String imageKey) {
    }

    @PostMapping("/message")
    public void message(@RequestBody Incoming in) {
        // channel 可指定其他适配器（如 feishu）驱动其回推链路；默认 fake 不出网
        adapter.emit(new BotMessage(
                in.channel() == null || in.channel().isBlank() ? "fake" : in.channel(),
                in.externalChatId(),
                in.chatType() == null ? BotMessage.CHAT_TYPE_P2P : in.chatType(),
                in.senderId(), in.text(), Boolean.TRUE.equals(in.mentioned()),
                in.messageId(), in.eventId(), in.imageKey()));
    }

    @GetMapping("/replies")
    public List<FakeBotAdapter.Reply> replies(@RequestParam(required = false) String chatId) {
        return adapter.replies().stream()
                .filter(r -> chatId == null || chatId.equals(r.externalChatId()))
                .toList();
    }

    /** M5：出站图片捕获拉取（/req 详情回推的需求截图）。 */
    @GetMapping("/images")
    public List<FakeBotAdapter.ImageReply> images(@RequestParam(required = false) String chatId) {
        return adapter.images().stream()
                .filter(r -> chatId == null || chatId.equals(r.externalChatId()))
                .toList();
    }

    /** M6 FR-20：主动推送捕获拉取（会话终态进展；与 replies 分开——推送无入站 origin）。 */
    @GetMapping("/pushes")
    public List<FakeBotAdapter.Reply> pushes(@RequestParam(required = false) String chatId) {
        return adapter.pushes().stream()
                .filter(r -> chatId == null || chatId.equals(r.externalChatId()))
                .toList();
    }

    // ---------------- CAP-76 卡片 ----------------

    public record CardActionIn(String channel, String externalChatId, String chatType, String senderId,
                               String messageId, String eventId, Map<String, Object> value) {
    }

    /** 注入卡片按钮回调（模拟飞书 card.action.trigger）。 */
    @PostMapping("/card-action")
    public void cardAction(@RequestBody CardActionIn in) {
        adapter.emitCardAction(new BotCardAction(
                in.channel() == null || in.channel().isBlank() ? "fake" : in.channel(),
                in.externalChatId(), in.chatType(), in.senderId(), in.messageId(), in.eventId(), in.value()));
    }

    /** 卡片出站捕获（发卡/更新卡）。 */
    @GetMapping("/cards")
    public List<FakeBotAdapter.CardReply> cards(@RequestParam(required = false) String chatId) {
        return adapter.cards().stream()
                .filter(c -> chatId == null || chatId.equals(c.externalChatId()))
                .toList();
    }

    @PostMapping("/reset")
    public void reset() {
        adapter.reset();
    }
}
