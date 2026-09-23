package com.devmind.classify.instance;

import com.devmind.classify.config.ClassifyProperties;
import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.ModelCallException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-57 FR-02 健康轮询：对 STARTING/RUNNING/UNHEALTHY 实例周期打 {@code /healthz}
 * （照 CAP-56 ServeCheckService 先例，异常消息已脱敏可直接落 last_error）。
 *
 * <p>状态翻转口径：healthz 通 → RUNNING（顺手存快照：槽位/设备/sources 详情页要渲染）；
 * 失败 → STARTING 且在宽限期（{@code devmind.classify.start-grace-ms}，默认 10min，GB 权重
 * 加载慢）内保持 STARTING，其余转 UNHEALTHY。<b>UNHEALTHY 不自动拉起</b>（CAP-57 非目标：
 * 无守护进程）——标红等人来，「重试启动」是人工动作。</p>
 */
@Service
public class ClassifyHealthPoller {

    private static final Logger log = LoggerFactory.getLogger(ClassifyHealthPoller.class);

    private static final List<String> WATCHED = List.of(
            ClassifyInstanceEntity.STATUS_STARTING,
            ClassifyInstanceEntity.STATUS_RUNNING,
            ClassifyInstanceEntity.STATUS_UNHEALTHY);

    private final ClassifyInstanceRepository repo;
    private final ClassifyProperties props;
    private final ObjectMapper mapper;

    public ClassifyHealthPoller(ClassifyInstanceRepository repo, ClassifyProperties props,
                                ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    @Scheduled(initialDelayString = "${devmind.classify.health-initial-delay-ms:15000}",
            fixedDelayString = "${devmind.classify.health-interval-ms:30000}")
    public void poll() {
        List<ClassifyInstanceEntity> targets;
        try {
            targets = repo.findByStatusIn(WATCHED);
        } catch (Exception e) {
            log.warn("健康轮询查询失败（下轮重试）: {}", e.getMessage());
            return;
        }
        for (ClassifyInstanceEntity e : targets) {
            try {
                pollOne(e);
            } catch (Exception ex) {
                // 单个实例的意外（序列化失败等）不拖垮整轮
                log.warn("健康轮询异常: instance={} err={}", e.getId(), ex.getMessage());
            }
        }
    }

    void pollOne(ClassifyInstanceEntity e) {
        Instant now = Instant.now();
        try {
            LayaDecisionClient.Health health = LayaDecisionClient.healthz(
                    new LayaDecisionClient.Options(e.getBaseUrl(), null, null,
                            props.getHealthTimeoutSeconds()));
            boolean recover = !ClassifyInstanceEntity.STATUS_RUNNING.equals(e.getStatus());
            e.setStatus(ClassifyInstanceEntity.STATUS_RUNNING);
            e.setLastHealthAt(now);
            e.setLastHealthJson(snapshotJson(health));
            e.setLastError(null);
            if (recover) {
                log.info("分类实例恢复健康: id={} name={} {}", e.getId(), e.getName(), health.summary());
            }
        } catch (ModelCallException ex) {
            boolean inGrace = ClassifyInstanceEntity.STATUS_STARTING.equals(e.getStatus())
                    && e.getLastStartAt() != null
                    && now.toEpochMilli() - e.getLastStartAt().toEpochMilli() < props.getStartGraceMs();
            if (!inGrace) {
                boolean degrade = !ClassifyInstanceEntity.STATUS_UNHEALTHY.equals(e.getStatus());
                e.setStatus(ClassifyInstanceEntity.STATUS_UNHEALTHY);
                if (degrade) {
                    log.warn("分类实例不健康: id={} name={} err={}", e.getId(), e.getName(), ex.getMessage());
                }
            }
            e.setLastError(trimTo(ex.getMessage(), 1024));
        }
        e.setUpdatedAt(now);
        repo.save(e);
    }

    /** healthz 快照原样落 JSON（status/version/loaded/devices/sources——详情页直接渲染） */
    private String snapshotJson(LayaDecisionClient.Health health) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", health.status());
        snapshot.put("layaVersion", health.layaVersion());
        snapshot.put("loaded", health.loaded() == null ? List.of() : health.loaded());
        snapshot.put("devices", health.devices() == null ? Map.of() : health.devices());
        snapshot.put("sources", health.sources() == null ? Map.of() : health.sources());
        snapshot.put("summary", health.summary());
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
