package com.devmind.usage.service;

import com.devmind.common.agent.runtime.TurnUsage;
import com.devmind.common.usage.UsageRecordSink;
import com.devmind.usage.model.UsageRecordEntity;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageRecordRepository;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 逐回合账本落库（{@link UsageRecordSink} 实现）。会话/问答的 onTurnResult 回调经
 * ObjectProvider 探测调用——调用方在事件读取线程上，本类只做一次 PK 查询 + 一次 INSERT
 * （与既有 addUsage 的同步 UPDATE 同量级），不引入异步（避免关闭丢账/乱序）。
 *
 * <p>归属列（owner/model）写时从会话实体快照：实体缺席（极端时序下会话行未落库/已删）
 * 也照写 null 归属，不丢账。全方法自吞异常——账务问题不打断会话事件流（SPI 契约）。</p>
 */
@Service
public class UsageRecordService implements UsageRecordSink {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordService.class);

    private final UsageRecordRepository recordRepo;
    private final UsageSessionStatsRepository sessionRepo;
    private final UsageChatStatsRepository chatRepo;

    public UsageRecordService(UsageRecordRepository recordRepo,
                              UsageSessionStatsRepository sessionRepo,
                              UsageChatStatsRepository chatRepo) {
        this.recordRepo = recordRepo;
        this.sessionRepo = sessionRepo;
        this.chatRepo = chatRepo;
    }

    @Override
    public void record(String source, String sessionId, TurnUsage usage) {
        try {
            UsageRecordEntity e = new UsageRecordEntity();
            e.setSource(source);
            e.setSessionId(sessionId);
            e.setCreatedAt(Instant.now());
            e.setDurationMs(usage.durationMs());
            e.setInputTokens(usage.inputTokens());
            e.setOutputTokens(usage.outputTokens());
            e.setCacheReadTokens(usage.cacheReadTokens());
            e.setCacheCreationTokens(usage.cacheCreationTokens());
            e.setCostUsd(usage.costUsd());
            // 归属快照：CLI result 帧不带 model，从会话实体列取；实体缺席照写 null 不丢账
            if (SOURCE_SESSION.equals(source)) {
                sessionRepo.findById(sessionId).ifPresent(s -> {
                    e.setOwner(s.getCreatedBy());
                    e.setModel(s.getModel());
                });
            } else if (SOURCE_CHAT.equals(source)) {
                chatRepo.findById(sessionId).ifPresent(c -> {
                    e.setOwner(c.getCreatedBy());
                    e.setModel(c.getModel());
                });
            } else {
                log.warn("回合账本未知来源: source={} id={}", source, sessionId);
                return;
            }
            recordRepo.save(e);
        } catch (Exception ex) {
            log.warn("回合账本落库失败: source={} id={} err={}", source, sessionId, ex.getMessage());
        }
    }
}
