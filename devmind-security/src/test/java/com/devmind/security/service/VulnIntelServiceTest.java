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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

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
        service = new VulnIntelService(repo, props, mapper);
    }

    private static VulnIntelEntity copy(VulnIntelEntity e) {
        VulnIntelEntity c = new VulnIntelEntity();
        c.setCve(e.getCve());
        c.setEpssScore(e.getEpssScore());
        c.setEpssPercentile(e.getEpssPercentile());
        c.setKev(e.isKev());
        c.setKevDueDate(e.getKevDueDate());
        c.setDescription(e.getDescription());
        c.setPublishedAt(e.getPublishedAt());
        c.setLastModifiedAt(e.getLastModifiedAt());
        c.setCvssScore(e.getCvssScore());
        c.setCvssSeverity(e.getCvssSeverity());
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

    @Test
    void 情报关闭时手动同步拒绝触发() {
        props.setIntelEnabled(false);

        assertFalse(service.triggerSyncEpss());
        assertFalse(service.triggerSyncKev());
        assertFalse(service.triggerSyncNvd());
        assertFalse(service.isEpssSyncRunning());
        assertFalse(service.isKevSyncRunning());
        assertFalse(service.isNvdSyncRunning());
    }

    // ---------- FR-10-5：NVD 元数据（描述/发布/修改/CVSS） ----------

    @Test
    void EPSS更新保留已有NVD元数据() {
        VulnIntelEntity old = row("CVE-META", 0.1, 0.2);
        old.setDescription("NVD 描述");
        old.setPublishedAt(Instant.parse("2024-01-01T00:00:00Z"));
        old.setLastModifiedAt(Instant.parse("2024-06-01T00:00:00Z"));
        old.setCvssScore(9.8);
        old.setCvssSeverity("CRITICAL");
        store.put("CVE-META", old);

        int written = service.upsertEpssChunk(List.of(row("CVE-META", 0.5, 0.6)));

        assertEquals(1, written);
        VulnIntelEntity saved = store.get("CVE-META");
        assertEquals("NVD 描述", saved.getDescription(), "EPSS 覆写不得清掉 NVD 描述");
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), saved.getPublishedAt());
        assertEquals(Instant.parse("2024-06-01T00:00:00Z"), saved.getLastModifiedAt());
        assertEquals(9.8, saved.getCvssScore());
        assertEquals("CRITICAL", saved.getCvssSeverity());
    }

    @Test
    void KEV简述仅在描述为空时兜底() {
        VulnIntelEntity hasDesc = row("CVE-HAS-DESC", null, null);
        hasDesc.setDescription("NVD 描述");
        store.put("CVE-HAS-DESC", hasDesc);

        Map<String, VulnIntelService.KevMeta> entries = new HashMap<>();
        entries.put("CVE-HAS-DESC", new VulnIntelService.KevMeta("2026-01-01", "Name A", "KEV 简述"));
        entries.put("CVE-NO-DESC", new VulnIntelService.KevMeta("2026-02-01", "Name B", "KEV 兜底描述"));

        int written = service.upsertKevEntries(entries);

        assertEquals(2, written);
        assertEquals("NVD 描述", store.get("CVE-HAS-DESC").getDescription(), "已有 NVD 描述不被 KEV 一句话覆写");
        assertEquals("KEV 兜底描述", store.get("CVE-NO-DESC").getDescription());
        assertTrue(store.get("CVE-NO-DESC").isKev());
        assertEquals("2026-02-01", store.get("CVE-NO-DESC").getKevDueDate());
    }

    @Test
    void NVD元数据解析含描述与CVSS31() throws Exception {
        String json = """
                {"id":"CVE-2024-3094",
                 "published":"2024-03-29T10:15:07.123","lastModified":"2024-04-01T13:14:00.000",
                 "descriptions":[{"lang":"es","value":"es 忽略"},{"lang":"en","value":"Malicious code in xz."}],
                 "metrics":{"cvssMetricV31":[{"cvssData":{"baseScore":10.0,"baseSeverity":"CRITICAL"}}]}}
                """;
        VulnIntelEntity e = new VulnIntelEntity();

        service.applyNvdItem(e, mapper.readTree(json));

        assertEquals(Instant.parse("2024-03-29T10:15:07.123Z"), e.getPublishedAt());
        assertEquals(Instant.parse("2024-04-01T13:14:00Z"), e.getLastModifiedAt());
        assertEquals("Malicious code in xz.", e.getDescription(), "只取 en 描述");
        assertEquals(10.0, e.getCvssScore());
        assertEquals("CRITICAL", e.getCvssSeverity());
    }

    @Test
    void NVD解析CVSSv2兜底() throws Exception {
        String json = """
                {"id":"CVE-2017-0144","published":"2017-03-17T00:59:00.000",
                 "lastModified":"2017-03-17T00:59:02.000","descriptions":[],
                 "metrics":{"cvssMetricV2":[{"cvssData":{"baseScore":9.3},"baseSeverity":"HIGH"}]}}
                """;
        VulnIntelEntity e = new VulnIntelEntity();

        service.applyNvdItem(e, mapper.readTree(json));

        assertEquals(9.3, e.getCvssScore());
        assertEquals("HIGH", e.getCvssSeverity());
    }

    @Test
    void NVD增量upsert不动EPSS与KEV字段() throws Exception {
        VulnIntelEntity old = row("CVE-X", 0.5, 0.9);
        old.setKev(true);
        old.setKevDueDate("2026-01-01");
        store.put("CVE-X", old);
        String json = """
                {"id":"CVE-X","published":"2024-01-01T00:00:00.000","lastModified":"2024-02-01T00:00:00.000",
                 "descriptions":[{"lang":"en","value":"desc"}],
                 "metrics":{"cvssMetricV31":[{"cvssData":{"baseScore":7.5,"baseSeverity":"HIGH"}}]}}
                """;

        int written = service.upsertNvdChunk(List.of(mapper.readTree(json)));

        assertEquals(1, written);
        VulnIntelEntity saved = store.get("CVE-X");
        assertEquals(0.5, saved.getEpssScore(), "NVD 同步不得动 EPSS 分数");
        assertTrue(saved.isKev(), "NVD 同步不得动 KEV 标记");
        assertEquals("2026-01-01", saved.getKevDueDate());
        assertEquals("desc", saved.getDescription());
        assertEquals(7.5, saved.getCvssScore());
    }

    @Test
    void NVD时间戳解析容错() {
        assertEquals(Instant.parse("2021-04-23T10:14:13.867Z"),
                VulnIntelService.parseNvdInstant("2021-04-23T10:14:13.867"));
        assertNull(VulnIntelService.parseNvdInstant(null));
        assertNull(VulnIntelService.parseNvdInstant(""));
        assertNull(VulnIntelService.parseNvdInstant("not-a-time"));
    }
}
