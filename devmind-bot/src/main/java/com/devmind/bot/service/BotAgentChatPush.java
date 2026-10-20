package com.devmind.bot.service;

import com.devmind.bot.spi.BotAdapter;
import com.devmind.common.notification.AgentChatPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link AgentChatPush} 的 bot 实现（CAP-76 FR-34）：按 channel 找适配器发送文本。
 * 注册表/适配器缺席或发送失败只记日志——推送是增强能力，不阻断计划收口。
 */
@Component
public class BotAgentChatPush implements AgentChatPush {

    private static final Logger log = LoggerFactory.getLogger(BotAgentChatPush.class);

    private final ObjectProvider<BotAdapterRegistry> registryProvider;

    public BotAgentChatPush(ObjectProvider<BotAdapterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public void pushText(String channel, String externalChatId, String text) {
        if (channel == null || channel.isBlank() || externalChatId == null || externalChatId.isBlank()) {
            return;
        }
        try {
            BotAdapterRegistry registry = registryProvider.getIfAvailable();
            BotAdapter adapter = registry == null ? null : registry.get(channel).orElse(null);
            if (adapter == null) {
                log.warn("聊天推送丢弃（适配器缺席）: channel={}", channel);
                return;
            }
            adapter.send(externalChatId, text);
        } catch (Exception e) {
            log.warn("聊天推送失败: channel={} chat={} err={}", channel, externalChatId, e.toString());
        }
    }
}
