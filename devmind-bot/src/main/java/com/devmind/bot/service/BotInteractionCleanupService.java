package com.devmind.bot.service;

import com.devmind.bot.repo.BotInteractionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 交互日志保留期清理（CAP-75 FR-24）：按保留期硬删 bot_interactions 超期行。
 *
 * <p>派生 delete 自带不了事务（save 有、delete 没有），而 @Scheduled 入口也不持有事务，
 * 故事务边界必须显式落在本服务方法上；定时任务侧保持无事务的瘦入口。</p>
 */
@Service
public class BotInteractionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(BotInteractionCleanupService.class);

    private final BotInteractionRepository repo;

    public BotInteractionCleanupService(BotInteractionRepository repo) {
        this.repo = repo;
    }

    /** 删除 created_at 早于 retentionDays 的行，返回删除行数。 */
    @Transactional
    public long purge(int retentionDays) {
        long deleted = repo.deleteByCreatedAtBefore(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("bot 交互日志清理: 删除 {} 条（保留 {} 天）", deleted, retentionDays);
        }
        return deleted;
    }
}
