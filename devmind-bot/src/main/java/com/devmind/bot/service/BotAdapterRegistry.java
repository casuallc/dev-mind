package com.devmind.bot.service;

import com.devmind.bot.spi.BotAdapter;
import com.devmind.bot.spi.BotMessageHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 适配器注册表（CAP-75 FR-04）：收集全部 {@link BotAdapter} 实现按 code 注册，
 * SmartLifecycle 随应用启停统一驱动 start/stop。单个适配器启动失败只降级自身
 * （告警日志），不拖垮应用与其他适配器。
 */
@Component
public class BotAdapterRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotAdapterRegistry.class);

    private final Map<String, BotAdapter> byCode = new LinkedHashMap<>();
    private final BotMessageHandler handler;
    private volatile boolean running = false;

    public BotAdapterRegistry(List<BotAdapter> adapters, BotMessageHandler handler) {
        this.handler = handler;
        for (BotAdapter a : adapters) {
            BotAdapter prev = byCode.put(a.code(), a);
            if (prev != null) {
                log.warn("bot 适配器 code 冲突：{} 覆盖 {}", a.code(), prev.getClass().getSimpleName());
            }
        }
    }

    public Optional<BotAdapter> get(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** 已注册适配器 code 列表（状态展示/排障用）。 */
    public List<String> codes() {
        return List.copyOf(byCode.keySet());
    }

    @Override
    public void start() {
        byCode.forEach((code, adapter) -> {
            try {
                adapter.start(handler);
                log.info("bot 适配器已启动: {}", code);
            } catch (Exception e) {
                // 降级为禁用：启动失败不阻断应用（契约要求实现自行兜，这里再兜一层）
                log.error("bot 适配器启动失败（已跳过）: {} err={}", code, e.getMessage(), e);
            }
        });
        running = true;
    }

    @Override
    public void stop() {
        byCode.forEach((code, adapter) -> {
            try {
                adapter.stop();
            } catch (Exception e) {
                log.warn("bot 适配器停止异常: {} err={}", code, e.getMessage());
            }
        });
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
