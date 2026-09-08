package com.devmind.common.agent;

import java.util.Optional;

/**
 * CAP-33 FR-05 问答上下文重建查找 SPI（chat 模块实现读 chat_sessions，session 模块的
 * ContextPackageProvider.find 重建路径消费）：runner 拉包未命中装配缓存时（服务端重启/
 * TTL 过期），session 侧先查 sessions 表，未命中再经本 SPI 按 chat 重建——
 * chat id 不在 sessions 表，没有本 SPI 重建会 404。
 */
public interface ChatContextLookup {

    Optional<ChatContextInfo> find(String chatId);

    /**
     * @param scenarioCode  创建时挂的场景（可空 = 无场景问答）
     * @param initialPrompt 首条消息全文（重建等价于 session 用 taskSpec 重装配的语义）
     */
    record ChatContextInfo(String scenarioCode, String initialPrompt) {
    }
}
