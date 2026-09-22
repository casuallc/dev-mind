package com.devmind.decision;

import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.decision.config.DecisionProperties;
import com.devmind.decision.record.DecisionRecordStore;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CAP-55 FR-03 装配检查：本模块在 app 里是"扫出来的 bean"，不是手工 new 的——单测用
 * {@code new HttpDecisionEngine(...)} 测策略，测不到"bean 压根没注册""配置前缀没绑上"
 * 这类只在启动时才现形的问题，所以这里用与 app 同款注解（{@code @Component} 扫描 +
 * {@code @ConfigurationPropertiesScan}）起一个最小上下文。
 *
 * <p><b>没有端点解析实现也要能装配</b>：devmind-model 不在场（或滚动升级期）时
 * ObjectProvider 拿不到实现，引擎必须照常注册并走降级，而不是启动失败。</p>
 *
 * <p><b>为什么要手工塞一个 repo</b>：扫 {@code com.devmind.decision} 会把 FR-05 的记录栈
 * （Store/Writer/Controller）一起扫进来，它们要的 {@code DecisionRecordRepository} 是 JPA
 * 生成的代理——本测试起的是裸上下文，没有 JPA 基础设施，不补这个 bean 会在 refresh 阶段
 * 报"找不到依赖"。这里补的是空壳 mock：本测试只关心 bean 在不在、配置绑没绑上。</p>
 */
class DecisionWiringTest {

    @Configuration
    @ConfigurationPropertiesScan("com.devmind.decision")
    static class ScanMe {
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> overrides) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.scan("com.devmind.decision");
        ctx.register(ScanMe.class);
        ctx.registerBean(DecisionRecordRepository.class, () -> mock(DecisionRecordRepository.class));
        if (!overrides.isEmpty()) {
            ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", overrides));
        }
        ctx.refresh();
        return ctx;
    }

    @Test
    void engineIsPickedUpByComponentScanWithoutAnyEndpointProvider() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of())) {
            DecisionEngine engine = ctx.getBean(DecisionEngine.class);

            assertInstanceOf(HttpDecisionEngine.class, engine);
            assertEquals(0, ctx.getBeanNamesForType(ModelEndpointProvider.class).length,
                    "本模块不带端点解析实现（那是 devmind-model 的事）");
            assertTrue(engine.unavailableReason().orElse("").contains("未装配"),
                    "缺实现不是启动失败，而是「配置侧就不可用」：" + engine.unavailableReason().orElse(""));
        }
    }

    @Test
    void propertiesBindFromDevmindDecisionPrefix() {
        try (AnnotationConfigApplicationContext ctx =
                     context(Map.of("devmind.decision.timeout-seconds", "7",
                             "devmind.decision.retry-count", "0",
                             "devmind.decision.retry-backoff-millis", "50"))) {
            DecisionProperties props = ctx.getBean(DecisionProperties.class);

            assertEquals(7, props.getTimeoutSeconds());
            assertEquals(0, props.getRetryCount());
            assertEquals(50, props.getRetryBackoffMillis());
        }
    }

    @Test
    void propertiesHaveUsableDefaults() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of())) {
            DecisionProperties props = ctx.getBean(DecisionProperties.class);

            assertEquals(3, props.getTimeoutSeconds(), "默认 3 秒：决策是同步交互路径，不能等太久");
            assertEquals(1, props.getRetryCount());
        }
    }

    /**
     * FR-05：记录 sink 必须是扫出来的 bean——知识库侧的裁决监听是按
     * {@code ObjectProvider<DecisionRecordSink>} 探测注入的，没注册就是"装配即静默不记录"，
     * 而且这种缺失在单测里完全看不出来（业务一切正常，只是数据集少了样本）。
     */
    @Test
    void recordSinkIsPickedUpByComponentScan() {
        try (AnnotationConfigApplicationContext ctx = context(Map.of())) {
            DecisionRecordSink sink = ctx.getBean(DecisionRecordSink.class);

            assertInstanceOf(DecisionRecordStore.class, sink);
        }
    }
}
