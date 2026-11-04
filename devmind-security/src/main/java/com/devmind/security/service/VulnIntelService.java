package com.devmind.security.service;

import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.repo.VulnIntelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * 漏洞情报同步（CAP-66 FR-10-4）：EPSS（FIRST.org CSV.gz 全量快照）与 CISA KEV 目录
 * 定时落 vuln_intel 表；归一化时按 CVE 查表富化（缺记录 → 字段 null，不阻塞扫描）。
 *
 * <p>红线：同步全失败仅告警日志不炸调度；{@code intel.enabled=false} 时整体跳过（离线环境）。</p>
 */
@Service
public class VulnIntelService {

    private static final Logger log = LoggerFactory.getLogger(VulnIntelService.class);
    /** 单批 upsert 规模（H2/MySQL IN 与批插都要限） */
    private static final int CHUNK = 500;

    private final VulnIntelRepository repo;
    private final SecurityProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public VulnIntelService(VulnIntelRepository repo, SecurityProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    /** 归一化富化查表：cve → intel（查无记录不出现在 map，调用方按 null 字段处理） */
    public Map<String, VulnIntelEntity> lookup(Collection<String> cves) {
        if (cves == null || cves.isEmpty()) {
            return Map.of();
        }
        return repo.findByCveIn(cves).stream()
                .collect(Collectors.toMap(VulnIntelEntity::getCve, Function.identity(), (a, b) -> a));
    }

    @Scheduled(cron = "${devmind.security.intel.epss-cron:0 0 4 * * *}")
    public void syncEpss() {
        if (!props.isIntelEnabled()) {
            return;
        }
        try {
            int n = doSyncEpss();
            log.info("EPSS 情报同步完成，共 {} 条", n);
        } catch (Exception e) {
            log.warn("EPSS 情报同步失败（数据源不可达则降级为空表，不阻塞扫描）: {}", e.toString());
        }
    }

    @Scheduled(cron = "${devmind.security.intel.kev-cron:0 30 4 * * *}")
    public void syncKev() {
        if (!props.isIntelEnabled()) {
            return;
        }
        try {
            int n = doSyncKev();
            log.info("KEV 情报同步完成，在榜 {} 条", n);
        } catch (Exception e) {
            log.warn("KEV 情报同步失败（数据源不可达则降级为空表，不阻塞扫描）: {}", e.toString());
        }
    }

    /** EPSS CSV.gz：首行以 # 起头（含模型版本注释），表头 cve,epss,percentile */
    int doSyncEpss() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getEpssUrl()))
                .timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("EPSS 数据源 HTTP " + resp.statusCode());
        }
        Instant now = Instant.now();
        int total = 0;
        List<VulnIntelEntity> batch = new ArrayList<>(CHUNK);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(resp.body()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("cve,")) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    continue;
                }
                VulnIntelEntity e = new VulnIntelEntity();
                e.setCve(parts[0].strip());
                e.setEpssScore(parseDouble(parts[1]));
                e.setEpssPercentile(parseDouble(parts[2]));
                e.setUpdatedAt(now);
                batch.add(e);
                if (batch.size() >= CHUNK) {
                    upsertEpssChunk(batch);
                    total += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            upsertEpssChunk(batch);
            total += batch.size();
        }
        return total;
    }

    /** EPSS 列合并：保留已有行的 KEV 标记（两数据源各自维护自己字段） */
    private void upsertEpssChunk(List<VulnIntelEntity> batch) {
        Map<String, VulnIntelEntity> existing = existingOf(batch);
        for (VulnIntelEntity e : batch) {
            VulnIntelEntity old = existing.get(e.getCve());
            if (old != null) {
                e.setKev(old.isKev());
                e.setKevDueDate(old.getKevDueDate());
            }
        }
        repo.saveAll(batch);
    }

    /** KEV JSON：vulnerabilities[]{cveID, dueDate,...}；退榜条目清 kev 标记 */
    int doSyncKev() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getKevUrl()))
                .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("KEV 数据源 HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        Map<String, String> dueDates = new HashMap<>();
        for (JsonNode v : root.path("vulnerabilities")) {
            String cve = v.path("cveID").asText("");
            if (!cve.isBlank()) {
                dueDates.put(cve, v.path("dueDate").asText(null));
            }
        }
        Instant now = Instant.now();
        // 在榜 upsert（保留 EPSS 字段）
        List<String> cves = new ArrayList<>(dueDates.keySet());
        for (int i = 0; i < cves.size(); i += CHUNK) {
            List<String> slice = cves.subList(i, Math.min(i + CHUNK, cves.size()));
            Map<String, VulnIntelEntity> existing = repo.findByCveIn(slice).stream()
                    .collect(Collectors.toMap(VulnIntelEntity::getCve, Function.identity()));
            List<VulnIntelEntity> batch = new ArrayList<>(slice.size());
            for (String cve : slice) {
                VulnIntelEntity e = existing.getOrDefault(cve, newVulnIntel(cve));
                e.setKev(true);
                e.setKevDueDate(dueDates.get(cve));
                e.setUpdatedAt(now);
                batch.add(e);
            }
            repo.saveAll(batch);
        }
        // 退榜清理：kev=true 但本次不在榜 → 清标记
        List<VulnIntelEntity> stale = repo.findAll().stream()
                .filter(VulnIntelEntity::isKev)
                .filter(e -> !dueDates.containsKey(e.getCve()))
                .peek(e -> {
                    e.setKev(false);
                    e.setKevDueDate(null);
                    e.setUpdatedAt(now);
                })
                .toList();
        if (!stale.isEmpty()) {
            repo.saveAll(stale);
        }
        return dueDates.size();
    }

    private Map<String, VulnIntelEntity> existingOf(List<VulnIntelEntity> batch) {
        Set<String> cves = batch.stream().map(VulnIntelEntity::getCve).collect(Collectors.toCollection(HashSet::new));
        return repo.findByCveIn(cves).stream()
                .collect(Collectors.toMap(VulnIntelEntity::getCve, Function.identity()));
    }

    private static VulnIntelEntity newVulnIntel(String cve) {
        VulnIntelEntity e = new VulnIntelEntity();
        e.setCve(cve);
        return e;
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (Exception e) {
            return null;
        }
    }
}
