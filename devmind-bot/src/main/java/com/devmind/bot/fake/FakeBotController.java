package com.devmind.bot.fake;

import com.devmind.bot.spi.BotMessage;
import java.util.List;
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

    public record Incoming(String externalChatId, String chatType, String senderId,
                           String text, Boolean mentioned, String messageId, String eventId) {
    }

    @PostMapping("/message")
    public void message(@RequestBody Incoming in) {
        adapter.emit(new BotMessage("fake", in.externalChatId(),
                in.chatType() == null ? BotMessage.CHAT_TYPE_P2P : in.chatType(),
                in.senderId(), in.text(), Boolean.TRUE.equals(in.mentioned()),
                in.messageId(), in.eventId()));
    }

    @GetMapping("/replies")
    public List<FakeBotAdapter.Reply> replies(@RequestParam(required = false) String chatId) {
        return adapter.replies().stream()
                .filter(r -> chatId == null || chatId.equals(r.externalChatId()))
                .toList();
    }

    @PostMapping("/reset")
    public void reset() {
        adapter.reset();
    }
}
