package com.devmind.chat.service;

import com.devmind.common.agent.ChatContextLookup;
import com.devmind.chat.repo.ChatSessionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * CAP-33 FR-05：{@link ChatContextLookup} 实现——session 模块的上下文包 find 重建路径
 * 经本 SPI 识别 chat id（chat id 不在 sessions 表），返回场景 code 与首条消息原文
 * （重渲染 {{task}} 的输入）。
 */
@Component
public class ChatContextLookupImpl implements ChatContextLookup {

    private final ChatSessionRepository chatRepo;

    public ChatContextLookupImpl(ChatSessionRepository chatRepo) {
        this.chatRepo = chatRepo;
    }

    @Override
    public Optional<ChatContextInfo> find(String chatId) {
        return chatRepo.findById(chatId)
                .map(e -> new ChatContextInfo(e.getScenarioCode(), e.getInitialPrompt()));
    }
}
