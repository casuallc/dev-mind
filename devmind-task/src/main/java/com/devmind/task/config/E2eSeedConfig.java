package com.devmind.task.config;

import com.devmind.task.builtin.EchoTaskHandler;
import com.devmind.task.api.ScheduleSpec;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.service.AsyncTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CAP-80 E2E 种子（仅 e2e profile）：任务创建只有 Java API（FR-10 无 REST 创建端点），
 * E2E 靠这里预置验证样本——
 * <ul>
 *   <li>即时回显（~4s 睡眠窗口，供 WS 实时 attach）</li>
 *   <li>FIXED_DELAY 周期回显（dedup 幂等登记）</li>
 *   <li>必失败可重试（attempt 1→2 后 FAILED，任务 DONE）</li>
 *   <li>不可重试失败（一次 FAILED 即终）</li>
 * </ul>
 */
@Component
@Profile("e2e")
public class E2eSeedConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eSeedConfig.class);

    private final AsyncTaskService service;

    public E2eSeedConfig(AsyncTaskService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        String immediateId = service.submit(EchoTaskHandler.TYPE,
                "{\"message\":\"e2e-immediate\",\"lines\":4,\"sleepMs\":4000}",
                TaskOptions.builder().name("e2e-即时回显").build());
        String retryId = service.submit(EchoTaskHandler.TYPE,
                "{\"message\":\"e2e-retry\",\"lines\":1,\"fail\":true}",
                TaskOptions.builder().name("e2e-重试失败").retryMaxAttempts(2)
                        .retryIntervalMs(2000L).build());
        String nonRetryId = service.submit(EchoTaskHandler.TYPE,
                "{\"message\":\"e2e-nonretry\",\"lines\":1,\"fail\":true,\"nonRetryable\":true}",
                TaskOptions.builder().name("e2e-不可重试").retryMaxAttempts(3).build());
        String periodicId = service.ensureScheduled("e2e-fixed-delay", EchoTaskHandler.TYPE,
                "{\"message\":\"e2e-periodic\",\"lines\":1}",
                ScheduleSpec.fixedDelay(10_000L).name("e2e-周期回显").build());
        log.info("CAP-80 E2E 种子就绪: immediate={} retry={} nonRetry={} periodic={}",
                immediateId, retryId, nonRetryId, periodicId);
    }
}
