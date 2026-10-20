package com.devmind.copilot.service;

import com.devmind.common.notification.BotCompletionWatch;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** 测试用 watch 钩子工厂：空实现（bot 未装配）与捕获实现（断言登记内容）。 */
final class TestWatchHooks {

    record Captured(String entityType, String entityId, String channel, String externalChatId,
                    String label) {
    }

    private TestWatchHooks() {
    }

    /** bot 未装配形态：getIfAvailable() = null，钩子应静默跳过。 */
    static CopilotCompletionWatchHook none() {
        return new CopilotCompletionWatchHook(providerOf(null));
    }

    /** 捕获形态：登记的 (entityType, entityId, channel, chatId, label) 追加进 sink。 */
    static CopilotCompletionWatchHook capturing(List<Captured> sink) {
        BotCompletionWatch watch = (entityType, entityId, channel, externalChatId, label) ->
                sink.add(new Captured(entityType, entityId, channel, externalChatId, label));
        return new CopilotCompletionWatchHook(providerOf(watch));
    }

    private static ObjectProvider<BotCompletionWatch> providerOf(BotCompletionWatch bean) {
        return new ObjectProvider<>() {
            @Override
            public BotCompletionWatch getObject(Object... args) {
                return bean;
            }

            @Override
            public BotCompletionWatch getObject() {
                return bean;
            }

            @Override
            public BotCompletionWatch getIfAvailable() {
                return bean;
            }

            @Override
            public Iterator<BotCompletionWatch> iterator() {
                return bean == null ? Collections.emptyIterator() : List.of(bean).iterator();
            }
        };
    }
}
