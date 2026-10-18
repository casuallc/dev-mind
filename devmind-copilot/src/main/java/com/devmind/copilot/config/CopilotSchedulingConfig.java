package com.devmind.copilot.config;

import com.devmind.copilot.service.CopilotActionService;
import com.devmind.copilot.service.CopilotCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

/**
 * CAP-76 FR-14/FR-29：分钟级确认单清扫与每日保留期清理是两个独立任务；
 * cron 置空即关闭对应任务。
 */
@Configuration
@EnableScheduling
public class CopilotSchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CopilotSchedulingConfig.class);

    private final CopilotProperties props;
    private final CopilotActionService actions;
    private final CopilotCleanupService cleanupService;

    public CopilotSchedulingConfig(CopilotProperties props,
                                   CopilotActionService actions,
                                   CopilotCleanupService cleanupService) {
        this.props = props;
        this.actions = actions;
        this.cleanupService = cleanupService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!props.isEnabled()) {
            return;
        }
        if (hasText(props.getActionSweepCron())) {
            registrar.addCronTask(this::sweep, props.getActionSweepCron());
        }
        if (hasText(props.getCleanupCron())) {
            registrar.addCronTask(this::runCleanup, props.getCleanupCron());
        }
    }

    private void sweep() {
        try {
            int changed = actions.sweep();
            if (changed > 0) {
                log.info("智能体确认单清扫：{} 条状态收敛", changed);
            }
        } catch (Exception e) {
            log.warn("智能体确认单清扫失败: {}", e.toString());
        }
    }

    private void runCleanup() {
        try {
            cleanupService.cleanup();
        } catch (Exception e) {
            log.warn("智能体审计清理失败: {}", e.toString());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
