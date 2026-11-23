package com.devmind.session.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CAP-114：工作区定时清理调度——固定每小时 tick，是否执行由 DB 策略判定
 * （enabled/runHour/今日未跑），策略改动即时生效无需重启。
 * 首跑延迟 5 分钟：避开启动期 runner 尚未重连的窗口（否则首轮全因节点离线失败）。
 */
@Component
public class WorkspaceCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupTask.class);

    private final WorkspaceCleanupService service;

    public WorkspaceCleanupTask(WorkspaceCleanupService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void tick() {
        try {
            service.tick();
        } catch (Exception e) {
            log.warn("工作区定时清理 tick 异常（下轮重试）: {}", e.getMessage());
        }
    }
}
