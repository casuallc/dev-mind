package com.devmind.bot.service;

import com.devmind.bot.model.BotJobWatchEntity;
import com.devmind.bot.repo.BotJobWatchRepository;
import com.devmind.common.notification.BotCompletionWatch;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作业终态推送订阅（CAP-75 FR-25）：{@link BotCompletionWatch} SPI 的 bot 侧实现——
 * 登记 / 消费 / 兜底清理。
 *
 * <p>SESSION 实体路由进既有 FR-20 会话 watch（复用富文案推送），其余实体入
 * {@code bot_job_watches}。登记失败只告警不抛：作业已受理成功，推送是增强能力，
 * 不能让订阅问题把执行回执打成失败（口径同 FR-20）。</p>
 */
@Service
public class BotJobWatchService implements BotCompletionWatch {

    private static final Logger log = LoggerFactory.getLogger(BotJobWatchService.class);
    /** label 落库截断（推送文案够用即可） */
    private static final int LABEL_MAX = 200;

    private final BotJobWatchRepository repo;
    private final BotSessionWatchService sessionWatches;

    public BotJobWatchService(BotJobWatchRepository repo, BotSessionWatchService sessionWatches) {
        this.repo = repo;
        this.sessionWatches = sessionWatches;
    }

    /** 登记订阅：SESSION 走会话 watch（label 即任务描述）；其余实体同实体重复登记覆盖旧行。 */
    @Override
    @Transactional
    public void watch(String entityType, String entityId, String channel, String externalChatId,
                      String label) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()
                || channel == null || channel.isBlank()
                || externalChatId == null || externalChatId.isBlank()) {
            return;
        }
        try {
            if (SESSION.equals(entityType)) {
                sessionWatches.register(entityId, channel, externalChatId, label);
                return;
            }
            repo.findByEntityTypeAndEntityId(entityType, entityId).ifPresent(repo::delete);
            BotJobWatchEntity w = new BotJobWatchEntity();
            w.setEntityType(entityType);
            w.setEntityId(entityId);
            w.setChannel(channel);
            w.setExternalChatId(externalChatId);
            w.setLabel(cut(label));
            repo.save(w);
        } catch (Exception e) {
            log.warn("bot 作业订阅登记失败（不阻断执行回执）: type={} id={} err={}",
                    entityType, entityId, e.getMessage());
        }
    }

    /**
     * 消费订阅（找到即删，返回行给调用方推送；无订阅/已消费为空）。
     * 一次性语义：取走即退订，推送失败也不补推（口径同 FR-20）。
     */
    @Transactional
    public Optional<BotJobWatchEntity> take(String entityType, String entityId) {
        Optional<BotJobWatchEntity> found = repo.findByEntityTypeAndEntityId(entityType, entityId);
        found.ifPresent(repo::delete);
        return found;
    }

    /** FR-24 兜底：清理超期残留，返回删除行数。 */
    @Transactional
    public long sweepOlderThan(Instant cutoff) {
        return repo.deleteByCreatedAtBefore(cutoff);
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= LABEL_MAX ? text : text.substring(0, LABEL_MAX);
    }
}
