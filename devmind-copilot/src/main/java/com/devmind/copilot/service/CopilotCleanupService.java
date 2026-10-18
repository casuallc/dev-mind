package com.devmind.copilot.service;

import com.devmind.copilot.config.CopilotProperties;
import com.devmind.copilot.model.CopilotActionEntity;
import com.devmind.copilot.model.CopilotConversationEntity;
import com.devmind.copilot.repo.CopilotActionRepository;
import com.devmind.copilot.repo.CopilotConversationRepository;
import com.devmind.copilot.repo.CopilotMessageRepository;
import com.devmind.copilot.repo.CopilotToolCallRepository;
import com.devmind.copilot.repo.CopilotTurnRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-76 FR-29 保留期清理：分层删除（turn → message/tool_call/action），
 * 与分钟级确认单清扫分属两个任务。
 *
 * <p>只删除终态确认单；PENDING/EXECUTING 由清扫任务先收敛，避免把"还能确认"的记录删掉。</p>
 */
@Service
public class CopilotCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CopilotCleanupService.class);

    private static final List<String> TERMINAL_ACTIONS = List.of(
            CopilotActionEntity.SUCCEEDED,
            CopilotActionEntity.FAILED,
            CopilotActionEntity.CANCELLED,
            CopilotActionEntity.EXPIRED);

    private final CopilotProperties props;
    private final CopilotTurnRepository turns;
    private final CopilotMessageRepository messages;
    private final CopilotToolCallRepository toolCalls;
    private final CopilotActionRepository actions;
    private final CopilotConversationRepository conversations;

    public CopilotCleanupService(CopilotProperties props,
                                 CopilotTurnRepository turns,
                                 CopilotMessageRepository messages,
                                 CopilotToolCallRepository toolCalls,
                                 CopilotActionRepository actions,
                                 CopilotConversationRepository conversations) {
        this.props = props;
        this.turns = turns;
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.actions = actions;
        this.conversations = conversations;
    }

    /** @return 删除的 turn 数（日志用） */
    @Transactional
    public long cleanup() {
        int days = Math.max(1, props.getAuditRetentionDays());
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long deletedTurns = turns.deleteByCreatedAtBefore(cutoff);
        long deletedMessages = messages.deleteByCreatedAtBefore(cutoff);
        long deletedCalls = toolCalls.deleteByCreatedAtBefore(cutoff);
        long deletedActions = actions.deleteByStatusInAndCreatedAtBefore(TERMINAL_ACTIONS, cutoff);
        int archived = 0;
        for (CopilotConversationEntity conv : conversations.findByLastTurnAtBefore(cutoff)) {
            if (!"ARCHIVED".equals(conv.getStatus())) {
                conv.setStatus("ARCHIVED");
                conversations.save(conv);
                archived++;
            }
        }
        log.info("智能体审计清理完成：保留 {} 天，turns={} messages={} toolCalls={} actions={} archivedConversations={}",
                days, deletedTurns, deletedMessages, deletedCalls, deletedActions, archived);
        return deletedTurns;
    }
}
