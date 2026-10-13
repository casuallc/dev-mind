package com.devmind.bot.fake;

import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotMessage;
import com.devmind.bot.spi.BotMessageHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E2E 假适配器（CAP-75 验证用，仅 e2e profile 装配）：code="fake"。
 * 不出网——入站由 FakeBotController 注入，回复记录在内存供断言拉取。
 */
@Component
@Profile("e2e")
public class FakeBotAdapter implements BotAdapter {

    public record Reply(String externalChatId, String text, long at) {
    }

    private volatile BotMessageHandler handler;
    private final List<Reply> replies = new CopyOnWriteArrayList<>();

    @Override
    public String code() {
        return "fake";
    }

    @Override
    public void start(BotMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void stop() {
        this.handler = null;
    }

    @Override
    public void reply(BotMessage origin, String text) {
        replies.add(new Reply(origin.externalChatId(), text, System.currentTimeMillis()));
    }

    /** 注入一条入站消息（同步走底座入口；业务仍在底座异步线程）。 */
    public void emit(BotMessage msg) {
        BotMessageHandler h = handler;
        if (h == null) {
            throw new IllegalStateException("fake 适配器未启动");
        }
        h.onMessage(msg);
    }

    public List<Reply> replies() {
        return List.copyOf(replies);
    }

    public void reset() {
        replies.clear();
    }
}
