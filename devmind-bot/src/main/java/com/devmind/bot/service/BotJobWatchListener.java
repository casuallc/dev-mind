package com.devmind.bot.service;

import com.devmind.bot.config.BotProperties;
import com.devmind.bot.model.BotJobWatchEntity;
import com.devmind.bot.spi.BotAdapter;
import com.devmind.common.event.SimpleDomainEvent;
import com.devmind.deploy.event.DeploymentCompletedEvent;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 作业终态主动推送（CAP-75 FR-25）：监听统一事件总线的作业终态事件
 * （{@code build.completed}/{@code test.completed} SimpleDomainEvent +
 * {@code deploy.completed} DeploymentCompletedEvent），命中 {@code bot_job_watches}
 * 订阅即向来源聊天推一条「✅/❌ 操作名 + 事件摘要 + 平台链接」。
 *
 * <p>一次性推送：取走订阅即删行，推送失败只记日志不补推。推送在独立虚拟线程执行
 * （事件发布线程不背网络 I/O），口径同 FR-20 会话推送。</p>
 */
@Component
public class BotJobWatchListener {

    private static final Logger log = LoggerFactory.getLogger(BotJobWatchListener.class);

    /** SimpleDomainEvent 事件类型 → 订阅实体类型（只认这两类作业；其余零开销返回） */
    private static final Map<String, String> EVENT_ENTITIES = Map.of(
            "build.completed", "BUILD",
            "test.completed", "TEST_RUN");
    private static final String DEPLOY_ENTITY = "DEPLOYMENT";

    private final BotJobWatchService watchService;
    private final ObjectProvider<BotAdapterRegistry> registryProvider;
    private final BotProperties props;
    private final ExecutorService pushExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public BotJobWatchListener(BotJobWatchService watchService,
                               ObjectProvider<BotAdapterRegistry> registryProvider,
                               BotProperties props) {
        this.watchService = watchService;
        this.registryProvider = registryProvider;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        pushExecutor.close();
    }

    @EventListener
    public void onJobCompleted(SimpleDomainEvent event) {
        String entityType = EVENT_ENTITIES.get(event.type());
        if (entityType == null || !entityType.equals(event.entityType())
                || event.entityId() == null || event.entityId().isBlank()) {
            return;
        }
        pushExecutor.execute(() -> push(entityType, event.entityId(), event.projectId(),
                event.summary(), Boolean.TRUE.equals(event.success())));
    }

    @EventListener
    public void onDeploymentCompleted(DeploymentCompletedEvent event) {
        pushExecutor.execute(() -> push(DEPLOY_ENTITY, String.valueOf(event.deploymentId()),
                event.projectId(),
                "部署 #" + event.deploymentId() + (event.success() ? " SUCCESS" : " FAILED"),
                event.success()));
    }

    /** 推送本体（包可见便于单测同步驱动）。订阅取走即删——无论推送成败都不重推。 */
    void push(String entityType, String entityId, String projectId, String summary, boolean success) {
        BotJobWatchEntity watch;
        try {
            Optional<BotJobWatchEntity> taken = watchService.take(entityType, entityId);
            if (taken.isEmpty()) {
                return; // 无订阅：非 bot 起的作业
            }
            watch = taken.get();
        } catch (Exception e) {
            log.warn("bot 作业订阅读取失败: type={} id={} err={}", entityType, entityId, e.getMessage());
            return;
        }
        BotAdapter adapter = adapterOf(watch.getChannel());
        if (adapter == null) {
            return; // 适配器没了（未装配/已移除）：订阅已消费，等同放弃推送
        }
        try {
            adapter.send(watch.getExternalChatId(), text(watch, projectId, summary, success));
        } catch (Exception e) {
            log.warn("bot 作业终态推送失败（不补推）: type={} id={} channel={} chat={} err={}",
                    entityType, entityId, watch.getChannel(), watch.getExternalChatId(), e.getMessage());
        }
    }

    /** 文案：✅/❌ 操作名 + 事件摘要行 + 平台链接（platformBaseUrl 未配置 = 不带）。 */
    private String text(BotJobWatchEntity watch, String projectId, String summary, boolean success) {
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "✅ " : "❌ ")
                .append(watch.getLabel() == null || watch.getLabel().isBlank() ? "作业" : watch.getLabel())
                .append(success ? " 已完成" : " 已失败");
        if (summary != null && !summary.isBlank()) {
            sb.append("\n").append(oneLine(summary, 200));
        }
        sb.append(footer(projectId));
        return sb.toString();
    }

    /** 按 channel 找适配器；注册表未装配/无适配器返回 null（调用方放弃推送）。 */
    private BotAdapter adapterOf(String channel) {
        BotAdapterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("bot 注册表未装配，作业终态推送丢弃: channel={}", channel);
            return null;
        }
        return registry.get(channel).orElse(null);
    }

    /** 平台链接尾注：落到项目（进入后同步当前项目），与 FR-20 会话推送同口径。 */
    private String footer(String projectId) {
        String base = props.getPlatformBaseUrl();
        if (base == null || base.isBlank() || projectId == null || projectId.isBlank()) {
            return "";
        }
        return "\n平台查看：" + base.replaceAll("/+$", "") + "/projects/" + projectId;
    }

    /** 摘要可能多行，推送里压成一行（截断加省略号）。 */
    private static String oneLine(String text, int max) {
        String one = text.strip().replaceAll("\\s+", " ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
