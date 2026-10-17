package com.devmind.bot.service;

import com.devmind.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * CAP-75 FR-24 定时清理：按保留期硬删交互日志，并顺带清掉 bot_session_watches 的残留
 * （FR-20 兜底——会话一直没结束/订阅没命中的行不该永久留）。
 *
 * <p>cron 外置可配 `devmind.bot.cleanup-cron`，默认每天 03:50（错开附件清理 03:40）；
 * 保留天数走 `devmind.bot.interaction-retention-days`（0 = 不清理交互日志，watch 兜底照样跑）。</p>
 */
@Component
public class BotInteractionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(BotInteractionCleanupTask.class);

    /** watch 残留只留 7 天：订阅本该在会话结束时就消费掉 */
    private static final int WATCH_SWEEP_DAYS = 7;

    private final BotInteractionCleanupService cleanupService;
    private final BotSessionWatchService watchService;
    private final BotProperties props;

    public BotInteractionCleanupTask(BotInteractionCleanupService cleanupService,
                                     BotSessionWatchService watchService,
                                     BotProperties props) {
        this.cleanupService = cleanupService;
        this.watchService = watchService;
        this.props = props;
    }

    @Scheduled(cron = "${devmind.bot.cleanup-cron:0 50 3 * * *}")
    public void cleanup() {
        int days = props.getInteractionRetentionDays();
        if (days > 0) {
            cleanupService.purge(days);
        }
        long stale = watchService.sweepOlderThan(
                Instant.now().minus(WATCH_SWEEP_DAYS, ChronoUnit.DAYS));
        if (stale > 0) {
            log.info("bot 会话订阅残留清理: 删除 {} 条（未结束/未推送的超期订阅）", stale);
        }
    }
}
