package com.devmind.agent.service;

import com.devmind.agent.config.AgentProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CAP-71 审计日志保留期清理：每日按 audit-retention-days（默认 180 天）硬删。
 * cron 外置可配（AttachmentCleanupTask 同款模式）。
 */
@Component
public class AgentAuditCleanupTask {

    private final AgentAuditLogService service;
    private final AgentProperties props;

    public AgentAuditCleanupTask(AgentAuditLogService service, AgentProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(cron = "${devmind.agent.audit-cleanup-cron:0 20 3 * * *}")
    public void purgeExpired() {
        service.purge(props.getAuditRetentionDays());
    }
}
