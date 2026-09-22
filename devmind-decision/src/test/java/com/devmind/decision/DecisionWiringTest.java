package com.devmind.decision;

import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.decision.config.DecisionProperties;
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

/**
 * CAP-55 FR-03 装配检查：本模块在 app 里是"扫出来的 bean"，不是手工 new 的——单测用
 * {@code new HttpDecisionEngine(...)} 测策略，测不到"bean 压根没注册""配置前缀没绑上"
 * 这类只在启动时才现形的问题，所以这里用与 app 同款注解（{@code @Component} 扫描 +
 * {@code @ConfigurationPropertiesScan}）起一个最小上下文。
 *
 * <p><b>没有端点解析实现也要能装配</b>：devmind-model 不在场（或滚动升级期）时
 * ObjectProvider 拿不到实现，引擎必须照常注册并走降级，而不是启动失败。</p>
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
}
