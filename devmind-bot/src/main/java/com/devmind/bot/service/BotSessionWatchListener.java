package com.devmind.bot.service;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.model.BotSessionWatchEntity;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.session.dto.SessionView;
import com.devmind.session.service.SessionManagerService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话终态主动推送（CAP-75 FR-20）：监听统一事件总线 {@code session.completed}
 * （SimpleDomainEvent，entityType=SESSION），命中 {@code bot_session_watches} 订阅即向
 * 起会话的那个聊天推一条「会话 xxx 完成/失败 + 任务 + 摘要 + 平台链接」。
 *
 * <p>一次性推送：取走订阅即删行，推送失败只记日志不补推。推送在独立虚拟线程执行
 * （事件的发布线程是会话 runtime 事件线程，网络 I/O 不占道、不拖慢其他监听器）。</p>
 */
@Component
public class BotSessionWatchListener {

    private static final Logger log = LoggerFactory.getLogger(BotSessionWatchListener.class);

    private static final String EVENT_TYPE = "session.completed";
    private static final String ENTITY_TYPE = "SESSION";
    private static final int TASK_MAX = 60;
    private static final int SUMMARY_MAX = 300;

    private final BotSessionWatchService watchService;
    private final SessionManagerService sessionManager;
    private final ObjectProvider<BotAdapterRegistry> registryProvider;
    private final BotProperties props;
    private final ExecutorService pushExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BotSessionWatchListener(BotSessionWatchService watchService,
                                   SessionManagerService sessionManager,
                                   ObjectProvider<BotAdapterRegistry> registryProvider,
                                   BotProperties props) {
        this.watchService = watchService;
        this.sessionManager = sessionManager;
        this.registryProvider = registryProvider;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        pushExecutor.close();
    }

    /** 只认「会话结束」事件：其余类型/实体零开销返回（总线上的监听器都会被叫一遍）。 */
    @EventListener
    public void onSessionCompleted(SimpleDomainEvent event) {
        if (!EVENT_TYPE.equals(event.type()) || !ENTITY_TYPE.equals(event.entityType())
                || event.entityId() == null || event.entityId().isBlank()) {
            return;
        }
        pushExecutor.execute(() -> push(event));
    }

    /** 推送本体（包可见便于单测同步驱动）。订阅取走即删——无论推送成败都不重推。 */
    void push(SimpleDomainEvent event) {
        BotSessionWatchEntity watch;
        try {
            Optional<BotSessionWatchEntity> taken = watchService.take(event.entityId());
            if (taken.isEmpty()) {
                return; // 无订阅：非 bot 起的会话
            }
            watch = taken.get();
        } catch (Exception e) {
            log.warn("bot 会话订阅读取失败: session={} err={}", event.entityId(), e.getMessage());
            return;
        }
        BotAdapter adapter = adapterOf(watch.getChannel());
        if (adapter == null) {
            return; // 适配器没了（未装配/已移除）：订阅已消费，等同放弃推送
        }
        try {
            adapter.send(watch.getExternalChatId(), text(event, watch));
        } catch (Exception e) {
            log.warn("bot 会话终态推送失败（不补推）: session={} channel={} chat={} err={}",
                    event.entityId(), watch.getChannel(), watch.getExternalChatId(), e.getMessage());
        }
    }

    /**
     * 文案：终态行 + 任务 + 摘要截断 + 平台链接。
     * 会话详情读不到（已删/读失败）不报错——任务退回订阅时留的 taskSpec，摘要省略。
     */
    private String text(SimpleDomainEvent event, BotSessionWatchEntity watch) {
        boolean success = Boolean.TRUE.equals(event.success());
        SessionView s = sessionOf(event.entityId());
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "✅ " : "❌ ").append("会话 ").append(shortId(event.entityId()))
                .append(success ? " 已完成" : " 已失败");
        String task = s == null ? watch.getTaskSpec() : s.taskSpec();
        if (task != null && !task.isBlank()) {
            sb.append("\n任务：").append(oneLine(task, TASK_MAX));
        }
        if (s != null && s.summary() != null && !s.summary().isBlank()) {
            sb.append("\n摘要：").append(oneLine(s.summary(), SUMMARY_MAX));
        }
        if (s != null && s.projectId() != null && !s.projectId().isBlank()) {
            sb.append(footer("/projects/" + s.projectId()));
        }
        sb.append("\n发 /status 看最近会话");
        return sb.toString();
    }

    private SessionView sessionOf(String sessionId) {
        try {
            return sessionManager.get(sessionId);
        } catch (Exception e) {
            log.warn("bot 终态推送读会话详情失败，退回订阅摘要: session={} err={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /** 按 channel 找适配器；注册表未装配/无适配器返回 null（调用方放弃推送）。 */
    private BotAdapter adapterOf(String channel) {
        BotAdapterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("bot 注册表未装配，终态推送丢弃: channel={}", channel);
            return null;
        }
        return registry.get(channel).orElse(null);
    }

    /** 平台链接尾注（platformBaseUrl 未配置 = 不带），与 BotRouterService 同口径。 */
    private String footer(String path) {
        String base = props.getPlatformBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return "\n平台查看：" + base.replaceAll("/+$", "") + path;
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }

    /** 摘要常是多行 markdown，推送里压成一行（截断加省略号）。 */
    private static String oneLine(String text, int max) {
        String one = text.strip().replaceAll("\\s+", " ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
