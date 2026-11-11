package com.devmind.security.service;

import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.repo.VulnIntelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EPSS 增量落库（2026-11-11 194 OOM 事故后瘦身）：40 万行每天全量覆写改为
 * 「新 CVE 必写、score/percentile 绝对变化超阈值才更新」。
 * 假仓库内存 map 模拟存库，upsertEpssChunk 是唯一写路径。
 */
class VulnIntelServiceTest {

    private final Map<String, VulnIntelEntity> store = new HashMap<>();
    private final List<List<VulnIntelEntity>> saveCalls = new ArrayList<>();
    private VulnIntelService service;
    private final SecurityProperties props = new SecurityProperties();

    @BeforeEach
    void setUp() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "findByCveIn" -> {
                @SuppressWarnings("unchecked")
                Collection<String> cves = (Collection<String>) args[0];
                List<VulnIntelEntity> found = new ArrayList<>();
                for (String cve : cves) {
                    VulnIntelEntity e = store.get(cve);
                    if (e != null) found.add(copy(e));
                }
                yield found;
            }
            case "saveAll" -> {
                @SuppressWarnings("unchecked")
                List<VulnIntelEntity> batch = (List<VulnIntelEntity>) args[0];
                saveCalls.add(batch);
                for (VulnIntelEntity e : batch) store.put(e.getCve(), copy(e));
                yield batch;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        };
        VulnIntelRepository repo = (VulnIntelRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{VulnIntelRepository.class}, h);
        service = new VulnIntelService(repo, props, JsonMapper.builder().build());
    }

    private static VulnIntelEntity copy(VulnIntelEntity e) {
        VulnIntelEntity c = new VulnIntelEntity();
        c.setCve(e.getCve());
        c.setEpssScore(e.getEpssScore());
        c.setEpssPercentile(e.getEpssPercentile());
        c.setKev(e.isKev());
        c.setKevDueDate(e.getKevDueDate());
        c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }

    private static VulnIntelEntity row(String cve, Double score, Double percentile) {
        VulnIntelEntity e = new VulnIntelEntity();
        e.setCve(cve);
        e.setEpssScore(score);
        e.setEpssPercentile(percentile);
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void 新CVE必写旧行未超阈值不写() {
        store.put("CVE-OLD-SAME", row("CVE-OLD-SAME", 0.5, 0.9));
        store.put("CVE-OLD-MOVED", row("CVE-OLD-MOVED", 0.5, 0.9));

        int written = service.upsertEpssChunk(List.of(
                row("CVE-NEW", 0.1, 0.2),
                row("CVE-OLD-SAME", 0.5005, 0.9005),   // 变化 0.0005 < 阈值 0.001
                row("CVE-OLD-MOVED", 0.52, 0.91)       // 变化 0.02 > 阈值
        ));

        assertEquals(2, written);
        assertEquals(0.1, store.get("CVE-NEW").getEpssScore());
        assertEquals(0.5, store.get("CVE-OLD-SAME").getEpssScore(), "未超阈值不写库");
        assertEquals(0.52, store.get("CVE-OLD-MOVED").getEpssScore());
    }

    @Test
    void 已变化行的KEV标记保留() {
        VulnIntelEntity kevRow = row("CVE-KEV", 0.1, 0.3);
        kevRow.setKev(true);
        kevRow.setKevDueDate("2026-01-01");
        store.put("CVE-KEV", kevRow);

        int written = service.upsertEpssChunk(List.of(row("CVE-KEV", 0.4, 0.6)));

        assertEquals(1, written);
        assertTrue(store.get("CVE-KEV").isKev(), "EPSS 更新不得清掉 KEV 标记");
        assertEquals("2026-01-01", store.get("CVE-KEV").getKevDueDate());
    }

    @Test
    void KEV专属行首次拿到EPSS视为变化() {
        VulnIntelEntity kevOnly = row("CVE-KEV-ONLY", null, null);
        kevOnly.setKev(true);
        store.put("CVE-KEV-ONLY", kevOnly);

        int written = service.upsertEpssChunk(List.of(row("CVE-KEV-ONLY", 0.01, 0.1)));

        assertEquals(1, written);
        assertEquals(0.01, store.get("CVE-KEV-ONLY").getEpssScore());
    }

    @Test
    void 阈值0退化为全量覆写() {
        props.setEpssChangeThreshold(0);
        store.put("CVE-OLD", row("CVE-OLD", 0.5, 0.9));

        int written = service.upsertEpssChunk(List.of(row("CVE-OLD", 0.5, 0.9)));

        assertEquals(1, written);
        assertFalse(saveCalls.isEmpty());
    }

    @Test
    void 全无变化时不发起saveAll() {
        store.put("CVE-OLD", row("CVE-OLD", 0.5, 0.9));

        int written = service.upsertEpssChunk(List.of(row("CVE-OLD", 0.5001, 0.9001)));

        assertEquals(0, written);
        assertTrue(saveCalls.isEmpty(), "无变化不应调 saveAll");
    }
}
