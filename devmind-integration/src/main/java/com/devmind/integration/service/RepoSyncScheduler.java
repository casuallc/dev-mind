package com.devmind.integration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAP-29 全局仓库定时抓取：默认每 30 分钟遍历可抓取行（CLONE + READY + ACTIVE）逐库 fetch。
 * {@code @EnableScheduling} 已由 JiraSyncSchedulingConfig 自持（同模块不重复开启）。
 * AtomicBoolean 防重入（上一轮未跑完直接跳过本轮）。
 */
@Component
public class RepoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RepoSyncScheduler.class);

    private final GitRepoSyncService syncService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean enabled;

    public RepoSyncScheduler(GitRepoSyncService syncService,
                             @Value("${devmind.integration.repo-sync.enabled:true}") boolean enabled) {
        this.syncService = syncService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${devmind.integration.repo-sync.fixed-delay:PT30M}",
            initialDelayString = "${devmind.integration.repo-sync.initial-delay:PT1M}")
    public void syncAll() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮全局仓库抓取未结束，本轮跳过");
            return;
        }
        try {
            syncService.fetchAll();
        } catch (Exception e) {
            log.warn("全局仓库定时抓取异常: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
