package com.devmind.security.parse;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.engine.NormalizedFinding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OWASP dependency-check JSON 报告解析（CAP-66 FR-02，SCA 引擎）。
 *
 * <p>要点：构件坐标取 {@code packages[]} 的 purl（pkg:maven/group/artifact@version）；
 * 严重度按 CVSS v3 baseScore 分段（不信引擎 severity 字符串）；CPE 匹配证据
 * （identifiers + evidenceCollected 计数）透传 detail 供 AI 复核与人工研判；
 * 引擎原生 suppression（suppressedVulnerabilities）透传落库仅作展示（detail.engineSuppressed=true，
 * 平台层抑制才是唯一权威）；NVD 版本区间（vulnerableSoftware）透传供修复版本推荐。</p>
 */
public final class DependencyCheckJsonParser {

    public static final String ENGINE = "sca";

    private DependencyCheckJsonParser() {
    }

    public static List<NormalizedFinding> parse(byte[] report, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(report);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "dependency-check 报告解析失败（非合法 JSON）: " + e.getMessage());
        }
        List<NormalizedFinding> out = new ArrayList<>();
        for (JsonNode dep : root.path("dependencies")) {
            if (dep.path("isVirtual").asBoolean(false)) {
                continue;
            }
            Coord coord = coordOf(dep);
            Map<String, Object> depEvidence = evidenceSummary(dep);
            String scope = dep.path("scope").asText(null);
            collect(dep.path("vulnerabilities"), false, coord, depEvidence, scope, out);
            collect(dep.path("suppressedVulnerabilities"), true, coord, depEvidence, scope, out);
        }
        return out;
    }

    private static void collect(JsonNode vulns, boolean engineSuppressed, Coord coord,
                                Map<String, Object> depEvidence, String scope,
                                List<NormalizedFinding> out) {
        if (!vulns.isArray()) {
            return;
        }
        for (JsonNode v : vulns) {
            String cve = v.path("name").asText("");
            if (cve.isBlank()) {
                continue;
            }
            Double baseScore = null;
            String vector = null;
            JsonNode cvss = v.path("cvssv3");
            if (cvss.isMissingNode() || !cvss.path("baseScore").isNumber()) {
                cvss = v.path("cvssv4");
            }
            if (cvss.path("baseScore").isNumber()) {
                baseScore = cvss.path("baseScore").doubleValue();
                vector = cvss.path("attackVector").asText(null);
            }
            List<String> cwes = new ArrayList<>();
            for (JsonNode c : v.path("cwes")) {
                cwes.add(c.asText(""));
            }
            List<Map<String, String>> ranges = new ArrayList<>();
            for (JsonNode vs : v.path("vulnerableSoftware")) {
                JsonNode sw = vs.path("software");
                Map<String, String> range = new LinkedHashMap<>();
                putIfText(range, "cpe", sw.path("id"));
                putIfText(range, "versionStartIncluding", sw.path("versionStartIncluding"));
                putIfText(range, "versionStartExcluding", sw.path("versionStartExcluding"));
                putIfText(range, "versionEndIncluding", sw.path("versionEndIncluding"));
                putIfText(range, "versionEndExcluding", sw.path("versionEndExcluding"));
                if (!range.isEmpty()) {
                    ranges.add(range);
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("cve", cve);
            if (coord.groupId() != null) {
                detail.put("groupId", coord.groupId());
            }
            if (coord.artifactId() != null) {
                detail.put("artifactId", coord.artifactId());
            }
            if (coord.version() != null) {
                detail.put("depVersion", coord.version());
            }
            detail.put("description", truncate(v.path("description").asText(""), 4000));
            if (baseScore != null) {
                detail.put("cvssScore", baseScore);
            }
            if (vector != null) {
                detail.put("cvssVector", vector);
            }
            if (!cwes.isEmpty()) {
                detail.put("cwes", cwes);
            }
            detail.put("source", v.path("source").asText(""));
            detail.put("evidence", depEvidence);
            if (!ranges.isEmpty()) {
                detail.put("versionRanges", ranges);
            }
            if (engineSuppressed) {
                detail.put("engineSuppressed", true);
            }
            detail.put("fileName", coord.fileName());
            out.add(new NormalizedFinding(ENGINE, cve, severityOf(baseScore),
                    cve + " @ " + coord.ga() + ":" + coord.version(),
                    null, null, null,
                    cve, coord.groupId(), coord.artifactId(), coord.version(),
                    scope, null, detail));
        }
    }

    /** CVSS v3 分段（FR-02）：≥9.0 CRITICAL / ≥7.0 HIGH / ≥4.0 MEDIUM / ≥0.1 LOW / 0 或无分 INFO */
    static String severityOf(Double baseScore) {
        if (baseScore == null) {
            return NormalizedFinding.INFO;
        }
        if (baseScore >= 9.0) {
            return NormalizedFinding.CRITICAL;
        }
        if (baseScore >= 7.0) {
            return NormalizedFinding.HIGH;
        }
        if (baseScore >= 4.0) {
            return NormalizedFinding.MEDIUM;
        }
        if (baseScore >= 0.1) {
            return NormalizedFinding.LOW;
        }
        return NormalizedFinding.INFO;
    }

    /** CPE 匹配证据摘要（identifiers + evidenceCollected 各类计数），误报研判用 */
    private static Map<String, Object> evidenceSummary(JsonNode dep) {
        Map<String, Object> ev = new LinkedHashMap<>();
        List<String> cpes = new ArrayList<>();
        for (JsonNode id : dep.path("vulnerabilityIds")) {
            String cpe = id.path("id").asText("");
            if (!cpe.isBlank()) {
                cpes.add(cpe);
            }
        }
        if (!cpes.isEmpty()) {
            ev.put("cpe", cpes);
        }
        JsonNode collected = dep.path("evidenceCollected");
        for (String kind : new String[]{"vendorEvidence", "productEvidence", "versionEvidence"}) {
            JsonNode arr = collected.path(kind);
            if (arr.isArray() && !arr.isEmpty()) {
                List<Map<String, String>> items = new ArrayList<>();
                int limit = 0;
                for (JsonNode e : arr) {
                    if (++limit > 20) {
                        break; // 证据数组可能很长，detail 留前 20 条足够研判
                    }
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("type", e.path("type").asText(""));
                    item.put("confidence", e.path("confidence").asText(""));
                    item.put("name", e.path("name").asText(""));
                    item.put("value", truncate(e.path("value").asText(""), 200));
                    items.add(item);
                }
                ev.put(kind, items);
            }
        }
        return ev;
    }

    /** 构件坐标：优先 purl（pkg:maven/group/artifact@version），回落 fileName 剥版本 */
    private static Coord coordOf(JsonNode dep) {
        String fileName = dep.path("fileName").asText("");
        for (JsonNode pkg : dep.path("packages")) {
            String purl = pkg.path("id").asText("");
            Coord c = parsePurl(purl, fileName);
            if (c != null) {
                return c;
            }
        }
        // 回落：log4j-core-2.14.1.jar → artifact=log4j-core version=2.14.1
        String base = fileName.replaceAll("\\.(jar|war|ear|zip)$", "");
        int dash = base.lastIndexOf('-');
        if (dash > 0 && dash < base.length() - 1 && Character.isDigit(base.charAt(dash + 1))) {
            return new Coord(null, base.substring(0, dash), base.substring(dash + 1), fileName);
        }
        return new Coord(null, base, null, fileName);
    }

    static Coord parsePurl(String purl, String fileName) {
        // pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1
        if (purl == null || !purl.startsWith("pkg:maven/")) {
            return null;
        }
        String rest = purl.substring("pkg:maven/".length());
        int at = rest.lastIndexOf('@');
        String version = at > 0 ? rest.substring(at + 1) : null;
        String ga = at > 0 ? rest.substring(0, at) : rest;
        int q = ga.indexOf('?');
        if (q >= 0) {
            ga = ga.substring(0, q);
        }
        int slash = ga.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        return new Coord(ga.substring(0, slash), ga.substring(slash + 1), version, fileName);
    }

    private static void putIfText(Map<String, String> map, String key, JsonNode node) {
        String v = node.asText(null);
        if (v != null && !v.isBlank()) {
            map.put(key, v);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 构件坐标（groupId 可空 = purl 缺失的回落态） */
    public record Coord(String groupId, String artifactId, String version, String fileName) {
        String ga() {
            return groupId == null ? artifactId : groupId + ":" + artifactId;
        }
    }
}
