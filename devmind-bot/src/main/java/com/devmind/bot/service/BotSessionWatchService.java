package com.devmind.bot.service;

import com.devmind.bot.model.BotSessionWatchEntity;
import com.devmind.bot.repo.BotSessionWatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 会话终态推送订阅（CAP-75 FR-20）：登记 / 消费 / 退订。
 *
 * <p>派生 delete 需要事务（save 自带事务，delete 不带），故存取统一收在本服务——
 * 底座与监听器只调这里，不直接碰 repository。登记失败只告警不抛：会话已起成功，
 * 推送是增强能力，不能让订阅问题把 /session 的回执打成失败。</p>
 */
@Service
public class BotSessionWatchService {

    private static final Logger log = LoggerFactory.getLogger(BotSessionWatchService.class);
    /** taskSpec 落库截断（推送文案够用即可，全文以平台会话为准） */
    private static final int TASK_SPEC_MAX = 200;

    private final BotSessionWatchRepository repo;

    public BotSessionWatchService(BotSessionWatchRepository repo) {
        this.repo = repo;
    }

    /** 起会话成功后登记订阅（会话 id 唯一，重复登记覆盖旧行）。 */
    @Transactional
    public void register(String sessionId, String channel, String externalChatId, String taskSpec) {
        try {
            repo.findBySessionId(sessionId).ifPresent(repo::delete);
            BotSessionWatchEntity w = new BotSessionWatchEntity();
            w.setSessionId(sessionId);
            w.setChannel(channel);
            w.setExternalChatId(externalChatId);
            w.setTaskSpec(cut(taskSpec));
            repo.save(w);
        } catch (Exception e) {
            log.warn("bot 会话订阅登记失败（不阻断会话回执）: session={} err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 消费订阅（找到即删，返回行给调用方推送；无订阅/已消费为空）。
     * 一次性语义：取走即退订，推送失败也不补推（CAP-75 FR-20）。
     */
    @Transactional
    public Optional<BotSessionWatchEntity> take(String sessionId) {
        Optional<BotSessionWatchEntity> found = repo.findBySessionId(sessionId);
        found.ifPresent(repo::delete);
        return found;
    }

    /** bot /stop 主动停止的会话退订。 */
    @Transactional
    public void remove(String sessionId) {
        try {
            repo.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("bot 会话订阅退订失败: session={} err={}", sessionId, e.getMessage());
        }
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
        return text.length() <= TASK_SPEC_MAX ? text : text.substring(0, TASK_SPEC_MAX);
    }
}
