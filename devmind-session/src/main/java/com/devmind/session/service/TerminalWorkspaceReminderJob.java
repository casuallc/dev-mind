package com.devmind.session.service;

import com.devmind.common.notification.NotificationEvent;
import com.devmind.notification.NotificationPublisher;
import com.devmind.session.config.TerminalWorkspaceProperties;
import com.devmind.session.model.TerminalWorkspaceEntity;
import com.devmind.session.repo.TerminalWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * CAP-115 M3：终端工作区闲置提醒——固定每小时巡检（首跑延迟 5 分钟，对齐 CAP-114 模式），
 * 扫描 OPEN 且 last_active_at 超阈值（默认 3 天，{@code devmind.terminal.idle-remind-days}
 * 可配，0=关闭）的工作区发站内通知，<b>只提醒不自动释放</b>（终端工作区是用户手工操作的
 * 活跃资产，自动回收风险大于收益）；同一工作区按 reminded_at 去重，默认每 24h 最多提醒一次。
 */
@Component
public class TerminalWorkspaceReminderJob {

    private static final Logger log = LoggerFactory.getLogger(TerminalWorkspaceReminderJob.class);

    /** 通知事件类型（站内信 kind，前端通知中心据此归类） */
    static final String EVENT_KIND = "TERMINAL_WORKSPACE_IDLE";

    private final TerminalWorkspaceRepository terminalWorkspaceRepo;
    private final TerminalWorkspaceProperties props;
    private final NotificationPublisher notificationPublisher;

    public TerminalWorkspaceReminderJob(TerminalWorkspaceRepository terminalWorkspaceRepo,
                                        TerminalWorkspaceProperties props,
                                        NotificationPublisher notificationPublisher) {
        this.terminalWorkspaceRepo = terminalWorkspaceRepo;
        this.props = props;
        this.notificationPublisher = notificationPublisher;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void tick() {
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("终端工作区闲置提醒巡检异常（下轮重试）: {}", e.getMessage());
        }
    }

    /** 巡检本体（包可见便于单测同步驱动）。 */
    void runOnce() {
        if (props.getIdleRemindDays() <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.getIdleRemindDays(), ChronoUnit.DAYS);
        Instant remindGap = Instant.now().minus(props.getRemindIntervalHours(), ChronoUnit.HOURS);
        int reminded = 0;
        for (TerminalWorkspaceEntity e : terminalWorkspaceRepo.findByState(TerminalWorkspaceService.STATE_OPEN)) {
            Instant lastActive = e.getLastActiveAt() != null ? e.getLastActiveAt() : e.getCreatedAt();
            if (lastActive == null || lastActive.isAfter(cutoff)) {
                continue; // 未超龄
            }
            if (e.getRemindedAt() != null && e.getRemindedAt().isAfter(remindGap)) {
                continue; // 距上次提醒不足一个间隔
            }
            try {
                notificationPublisher.publish(NotificationEvent.of(EVENT_KIND, e.getWorkspaceKey(),
                        "终端工作区闲置提醒",
                        "终端工作区 " + e.getWorkspaceKey() + "（项目 " + e.getProjectId()
                                + "）已超过 " + props.getIdleRemindDays() + " 天未活跃，"
                                + "如改动已收尾请及时收口合入基线，不再需要可释放。"));
                e.setRemindedAt(Instant.now());
                terminalWorkspaceRepo.save(e);
                reminded++;
            } catch (Exception ex) {
                log.warn("终端工作区闲置提醒发送失败（下轮重试）: key={} err={}",
                        e.getWorkspaceKey(), ex.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("终端工作区闲置提醒: 本轮提醒 {} 个", reminded);
        }
    }
}
