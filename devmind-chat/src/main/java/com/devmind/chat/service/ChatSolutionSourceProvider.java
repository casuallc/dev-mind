package com.devmind.chat.service;

import com.devmind.common.knowledge.SolutionSourceProvider;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** CAP-84 FR-24：把问答模块的方案数据源接到 common SPI（knowledge 模块经此沉淀文档）。 */
@Component
public class ChatSolutionSourceProvider implements SolutionSourceProvider {

    private final ChatManagerService chatManagerService;

    public ChatSolutionSourceProvider(ChatManagerService chatManagerService) {
        this.chatManagerService = chatManagerService;
    }

    @Override
    public Optional<SolutionSource> find(String chatId, Long turnSeq) {
        return chatManagerService.solutionSource(chatId, turnSeq);
    }
}
