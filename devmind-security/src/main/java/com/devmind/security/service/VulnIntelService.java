package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.VulnIntelEntity;
import com.devmind.security.repo.VulnIntelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * 漏洞情报同步（CAP-66 FR-10-4/FR-10-5）：EPSS（FIRST.org CSV.gz 全量快照）、CISA KEV 目录
 * 与 NVD API 2.0 元数据（发布时间/修改时间/描述/CVSS）定时落 vuln_intel 表；
 * 归一化时按 CVE 查表富化（缺记录 → 字段 null，不阻塞扫描）。
 *
 * <p>NVD 三段式填充：①KEV JSON 自带的 shortDescription 零成本兜底；②每日增量窗口
 * （最近 N 小时发布/修改的 CVE，幂等 upsert，无需持久水位）；③回灌——每日对
 * 「EPSS 百分位最高且尚无描述」的行按限速逐个补拉，高分 CVE 覆盖率自愈收敛。</p>
 *
 * <p>红线：同步全失败仅告警日志不炸调度；{@code intel.enabled=false} 时整体跳过（离线环境）；
 * NVD 限流无 key 5 req/30s、有 key 50 req/30s——请求间固定 sleep（{@link #throttle()}）。</p>
 */
@Service
public class VulnIntelService {

    private static final Logger log = LoggerFactory.getLogger(VulnIntelService.class);
    /** 单批 upsert 规模（H2/MySQL IN 与批插都要限） */
    private static final int CHUNK = 500;
    /** description 列长 2048，入库截断余量 */
    private static final int DESC_MAX = 2000;
    /** NVD 增量窗口参数时间格式（API 要求 extended ISO-8601，UTC） */
    private static final DateTimeFormatter NVD_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    /** NVD 单页上限 */
    private static final int NVD_PAGE = 2000;

    /** KEV 条目元数据（修复期限 + 名称 + 简述；简述作 description 兜底） */
    record KevMeta(String dueDate, String vulnName, String shortDescription) {
    }

    private final VulnIntelRepository repo;
    private final SecurityProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 同步互斥旗标：定时任务与后台手动触发共用，避免两路并发把 40 万行处理压上堆（OOM 事故教训） */
    private final AtomicBoolean epssRunning = new AtomicBoolean();
    private final AtomicBoolean kevRunning = new AtomicBoolean();
    private final AtomicBoolean nvdRunning = new AtomicBoolean();

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
        if (!props.isIntelEnabled() || !epssRunning.compareAndSet(false, true)) {
            return; // 已关闭或已有同步在跑（含手动触发）
        }
        try {
            int n = doSyncEpss();
            log.info("EPSS 情报同步完成，共 {} 条", n);
        } catch (Exception e) {
            log.warn("EPSS 情报同步失败（数据源不可达则降级为空表，不阻塞扫描）: {}", e.toString());
        } finally {
            epssRunning.set(false);
        }
    }

    @Scheduled(cron = "${devmind.security.intel.kev-cron:0 30 4 * * *}")
    public void syncKev() {
        if (!props.isIntelEnabled() || !kevRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            int n = doSyncKev();
            log.info("KEV 情报同步完成，在榜 {} 条", n);
        } catch (Exception e) {
            log.warn("KEV 情报同步失败（数据源不可达则降级为空表，不阻塞扫描）: {}", e.toString());
        } finally {
            kevRunning.set(false);
        }
    }

    @Scheduled(cron = "${devmind.security.intel.nvd-cron:0 0 5 * * *}")
    public void syncNvd() {
        if (!props.isIntelEnabled() || !nvdRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            int n = doSyncNvd();
            log.info("NVD 情报同步完成，共 {} 条", n);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("NVD 情报同步被中断");
        } catch (Exception e) {
            log.warn("NVD 情报同步失败（数据源不可达则元数据暂缺，不阻塞扫描）: {}", e.toString());
        } finally {
            nvdRunning.set(false);
        }
    }

    /**
     * 后台漏洞情报页手动触发：异步跑（EPSS 全量下载+增量落库可达分钟级，不同步占 HTTP 连接）。
     *
     * @return false = 情报同步已关闭或上一轮还在跑（调用方据此回 409）
     */
    public boolean triggerSyncEpss() {
        if (!props.isIntelEnabled() || epssRunning.get()) {
            return false;
        }
        Thread.ofVirtual().start(this::syncEpss);
        return true;
    }

    public boolean triggerSyncKev() {
        if (!props.isIntelEnabled() || kevRunning.get()) {
            return false;
        }
        Thread.ofVirtual().start(this::syncKev);
        return true;
    }

    public boolean triggerSyncNvd() {
        if (!props.isIntelEnabled() || nvdRunning.get()) {
            return false;
        }
        Thread.ofVirtual().start(this::syncNvd);
        return true;
    }

    public boolean isEpssSyncRunning() {
        return epssRunning.get();
    }

    public boolean isKevSyncRunning() {
        return kevRunning.get();
    }

    public boolean isNvdSyncRunning() {
        return nvdRunning.get();
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
        int written = 0;
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
                    written += upsertEpssChunk(batch);
                    total += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            written += upsertEpssChunk(batch);
            total += batch.size();
        }
        log.info("EPSS 增量落库：扫描 {} 条，写入 {} 条（变化阈值 {}）", total, written, props.getEpssChangeThreshold());
        return total;
    }

    /**
     * EPSS 分块合并：新 CVE 直接插；已有行仅当 score/percentile 绝对变化超过
     * {@link SecurityProperties#getEpssChangeThreshold()} 才写——40 万行每天全量覆写既压堆
     * 又压 DB（2026-11-11 194 OOM 事故根因之一），而每日分数有实质变化的 CVE 只占极少数。
     * 已有行的 KEV 标记与 NVD 元数据（描述/发布/修改/CVSS）无条件保留（各由 doSyncKev/NVD 同步维护）。
     *
     * @return 实际写库条数
     */
    int upsertEpssChunk(List<VulnIntelEntity> batch) {
        Map<String, VulnIntelEntity> existing = existingOf(batch);
        List<VulnIntelEntity> dirty = new ArrayList<>(batch.size());
        for (VulnIntelEntity e : batch) {
            VulnIntelEntity old = existing.get(e.getCve());
            if (old == null) {
                dirty.add(e);
                continue;
            }
            e.setKev(old.isKev());
            e.setKevDueDate(old.getKevDueDate());
            e.setDescription(old.getDescription());
            e.setPublishedAt(old.getPublishedAt());
            e.setLastModifiedAt(old.getLastModifiedAt());
            e.setCvssScore(old.getCvssScore());
            e.setCvssSeverity(old.getCvssSeverity());
            if (deltaBeyond(old.getEpssScore(), e.getEpssScore())
                    || deltaBeyond(old.getEpssPercentile(), e.getEpssPercentile())) {
                dirty.add(e);
            }
        }
        if (!dirty.isEmpty()) {
            repo.saveAll(dirty);
        }
        return dirty.size();
    }

    /** 绝对变化量判定：旧值为 null（KEV-only 行首次拿到 EPSS）视为变化；双侧 null 视为不变。 */
    private boolean deltaBeyond(Double oldV, Double newV) {
        double threshold = props.getEpssChangeThreshold();
        if (threshold <= 0) {
            return true; // 阈值 0 = 全量覆写（旧行为）
        }
        if (oldV == null || newV == null) {
            return oldV != newV;
        }
        return Math.abs(oldV - newV) > threshold;
    }

    /** KEV JSON：vulnerabilities[]{cveID, dueDate, vulnerabilityName, shortDescription,...}；退榜条目清 kev 标记 */
    int doSyncKev() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getKevUrl()))
                .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("KEV 数据源 HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        Map<String, KevMeta> entries = new HashMap<>();
        for (JsonNode v : root.path("vulnerabilities")) {
            String cve = v.path("cveID").asText("");
            if (!cve.isBlank()) {
                entries.put(cve, new KevMeta(v.path("dueDate").asText(null),
                        v.path("vulnerabilityName").asText(null),
                        v.path("shortDescription").asText(null)));
            }
        }
        int written = upsertKevEntries(entries);
        Instant now = Instant.now();
        // 退榜清理：kev=true 但本次不在榜 → 清标记
        List<VulnIntelEntity> stale = repo.findAll().stream()
                .filter(VulnIntelEntity::isKev)
                .filter(e -> !entries.containsKey(e.getCve()))
                .peek(e -> {
                    e.setKev(false);
                    e.setKevDueDate(null);
                    e.setUpdatedAt(now);
                })
                .toList();
        if (!stale.isEmpty()) {
            repo.saveAll(stale);
        }
        log.info("KEV 落库：在榜 {} 条，退榜清理 {} 条", written, stale.size());
        return entries.size();
    }

    /**
     * KEV 在榜 upsert（保留 EPSS/NVD 字段；KEV 简述仅在描述为空时兜底填充，
     * 已有 NVD 描述不被覆写——NVD 描述信息量比 KEV 一句话大）。
     */
    int upsertKevEntries(Map<String, KevMeta> entries) {
        Instant now = Instant.now();
        List<String> cves = new ArrayList<>(entries.keySet());
        int written = 0;
        for (int i = 0; i < cves.size(); i += CHUNK) {
            List<String> slice = cves.subList(i, Math.min(i + CHUNK, cves.size()));
            Map<String, VulnIntelEntity> existing = repo.findByCveIn(slice).stream()
                    .collect(Collectors.toMap(VulnIntelEntity::getCve, Function.identity()));
            List<VulnIntelEntity> batch = new ArrayList<>(slice.size());
            for (String cve : slice) {
                KevMeta meta = entries.get(cve);
                VulnIntelEntity e = existing.getOrDefault(cve, newVulnIntel(cve));
                e.setKev(true);
                e.setKevDueDate(meta.dueDate());
                if (!StringUtils.hasText(e.getDescription()) && StringUtils.hasText(meta.shortDescription())) {
                    e.setDescription(truncate(meta.shortDescription()));
                }
                e.setUpdatedAt(now);
                batch.add(e);
            }
            repo.saveAll(batch);
            written += batch.size();
        }
        return written;
    }

    /**
     * NVD 增量同步：拉「最近 {@link SecurityProperties#getNvdWindowHours()} 小时内发布/修改」
     * 的 CVE 元数据分页 upsert（重叠幂等，无需持久水位），随后按配置回灌高分缺描述行。
     *
     * @return 写入条数（窗口更新 + 回灌）
     */
    int doSyncNvd() throws IOException, InterruptedException {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(Math.max(1, props.getNvdWindowHours())));
        String window = "?lastModStartDate=" + enc(NVD_TIME.format(start))
                + "&lastModEndDate=" + enc(NVD_TIME.format(end));
        int updated = 0;
        int startIndex = 0;
        while (true) {
            JsonNode root = fetchNvd(window + "&resultsPerPage=" + NVD_PAGE + "&startIndex=" + startIndex);
            List<JsonNode> cveNodes = new ArrayList<>();
            for (JsonNode v : root.path("vulnerabilities")) {
                cveNodes.add(v.path("cve"));
            }
            updated += upsertNvdChunk(cveNodes);
            int totalResults = root.path("totalResults").asInt(0);
            startIndex += cveNodes.size();
            if (cveNodes.isEmpty() || startIndex >= totalResults) {
                break;
            }
            throttle();
        }
        int backfilled = backfillNvdMetadata();
        log.info("NVD 增量落库：窗口 {}h 更新 {} 条，回灌 {} 条", props.getNvdWindowHours(), updated, backfilled);
        return updated + backfilled;
    }

    /** NVD 分块 upsert：已有行只覆写元数据（EPSS/KEV 字段不动），新行先建元数据骨架 */
    int upsertNvdChunk(List<JsonNode> cveNodes) {
        Instant now = Instant.now();
        int written = 0;
        for (int i = 0; i < cveNodes.size(); i += CHUNK) {
            List<JsonNode> slice = cveNodes.subList(i, Math.min(i + CHUNK, cveNodes.size()));
            List<String> ids = slice.stream()
                    .map(n -> n.path("id").asText(""))
                    .filter(s -> !s.isBlank())
                    .toList();
            Map<String, VulnIntelEntity> existing = repo.findByCveIn(ids).stream()
                    .collect(Collectors.toMap(VulnIntelEntity::getCve, Function.identity()));
            List<VulnIntelEntity> batch = new ArrayList<>(ids.size());
            for (JsonNode cve : slice) {
                String id = cve.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                VulnIntelEntity e = existing.getOrDefault(id, newVulnIntel(id));
                applyNvdItem(e, cve);
                e.setUpdatedAt(now);
                batch.add(e);
            }
            if (!batch.isEmpty()) {
                repo.saveAll(batch);
                written += batch.size();
            }
        }
        return written;
    }

    /**
     * 回灌：取「尚无描述、EPSS 百分位最高」的前 N 行逐个补拉 NVD（限速），
     * 让高分 CVE 的元数据覆盖率随每日同步自愈收敛，不做全量回填（30 万行 × 限流不现实）。
     */
    int backfillNvdMetadata() throws InterruptedException {
        int limit = props.getNvdBackfillBatch();
        if (limit <= 0) {
            return 0;
        }
        List<VulnIntelEntity> missing =
                repo.findByDescriptionIsNullOrderByEpssPercentileDesc(PageRequest.of(0, limit));
        int filled = 0;
        for (VulnIntelEntity e : missing) {
            try {
                if (enrich(e.getCve()).isPresent()) {
                    filled++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception ex) {
                log.debug("NVD 回灌 {} 失败（跳过，次日再试）: {}", e.getCve(), ex.toString());
            }
            throttle();
        }
        return filled;
    }

    /**
     * 按需补拉单个 CVE 的 NVD 元数据（后台情报页展开行时触发；查无此 CVE 返回 empty）。
     * 已存在行只覆写元数据字段，EPSS/KEV 不动；情报同步关闭（离线环境）→ 409。
     */
    public Optional<VulnIntelEntity> enrich(String cve) throws IOException, InterruptedException {
        if (!props.isIntelEnabled()) {
            throw new DevMindException(ErrorCode.CONFLICT, "情报同步已关闭（离线环境），无法补拉 NVD 元数据");
        }
        JsonNode root = fetchNvd("?cveId=" + enc(cve));
        for (JsonNode v : root.path("vulnerabilities")) {
            VulnIntelEntity e = repo.findById(cve).orElseGet(() -> newVulnIntel(cve));
            applyNvdItem(e, v.path("cve"));
            e.setUpdatedAt(Instant.now());
            return Optional.of(repo.save(e));
        }
        return Optional.empty();
    }

    /**
     * NVD cve 节点 → 实体元数据（描述取 en 摘要、截断 2000；CVSS 优先 v3.1 > v3.0 > v2；
     * published/lastModified 为无区号 ISO-8601，按 UTC 解析）。
     */
    void applyNvdItem(VulnIntelEntity e, JsonNode cve) {
        e.setPublishedAt(parseNvdInstant(cve.path("published").asText(null)));
        e.setLastModifiedAt(parseNvdInstant(cve.path("lastModified").asText(null)));
        for (JsonNode d : cve.path("descriptions")) {
            if ("en".equals(d.path("lang").asText(""))) {
                e.setDescription(truncate(d.path("value").asText(null)));
                break;
            }
        }
        JsonNode metrics = cve.path("metrics");
        JsonNode m = firstEntry(metrics.path("cvssMetricV31"));
        if (m == null) {
            m = firstEntry(metrics.path("cvssMetricV30"));
        }
        if (m != null) {
            JsonNode data = m.path("cvssData");
            e.setCvssScore(data.path("baseScore").isNumber() ? data.path("baseScore").asDouble() : null);
            e.setCvssSeverity(data.path("baseSeverity").asText(null));
            return;
        }
        JsonNode v2 = firstEntry(metrics.path("cvssMetricV2"));
        if (v2 != null) {
            JsonNode data = v2.path("cvssData");
            e.setCvssScore(data.path("baseScore").isNumber() ? data.path("baseScore").asDouble() : null);
            String severity = v2.path("baseSeverity").asText(null);
            e.setCvssSeverity(severity != null ? severity : v2.path("severity").asText(null));
        }
    }

    private JsonNode fetchNvd(String query) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.getNvdUrl() + query))
                .timeout(Duration.ofMinutes(2)).GET();
        if (StringUtils.hasText(props.getNvdApiKey())) {
            b.header("apiKey", props.getNvdApiKey());
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("NVD 数据源 HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }

    /** NVD 限流：无 key 5 req/30s → 6.5s 间隔；有 key 50 req/30s → 0.7s 间隔 */
    private void throttle() throws InterruptedException {
        Thread.sleep(StringUtils.hasText(props.getNvdApiKey()) ? 700 : 6500);
    }

    private static JsonNode firstEntry(JsonNode array) {
        for (JsonNode n : array) {
            return n;
        }
        return null;
    }

    /** NVD 时间戳无区号（如 2021-04-23T10:14:13.867），按 UTC 解析；解析失败置 null */
    static Instant parseNvdInstant(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        return v.length() <= DESC_MAX ? v : v.substring(0, DESC_MAX);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
