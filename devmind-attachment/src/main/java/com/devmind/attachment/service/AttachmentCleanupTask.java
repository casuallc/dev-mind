package com.devmind.attachment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CAP-68 过期附件定时清理：硬删 expires_at < now（删行+删盘，不做引用检查——
 * 被历史消息/文档引用的附件到期照样删，引用处 404/裂图由用户自担，CAP 文档已明示）。
 * cron 可配 devmind.attachment.cleanup-cron，默认每天 03:40。
 */
@Component
public class AttachmentCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupTask.class);

    private final AttachmentService service;

    public AttachmentCleanupTask(AttachmentService service) {
        this.service = service;
    }

    @Scheduled(cron = "${devmind.attachment.cleanup-cron:0 40 3 * * *}")
    public void cleanup() {
        int n = service.cleanupExpired(Instant.now());
        if (n > 0) {
            log.info("过期附件清理完成: 删除 {} 个", n);
        }
    }
}
