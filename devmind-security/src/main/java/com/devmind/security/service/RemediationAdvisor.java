package com.devmind.security.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修复指导（CAP-66 FR-11-2/3）：从 NVD 版本区间推导最低安全版本 + 修复动作分类。
 *
 * <p><b>纯启发式红线</b>：推荐版本旁恒附来源区间原文（versionRangesRaw）；
 * 推导不出 → recommendedVersion=null + note「需人工查证」，<b>不编造版本号</b>。</p>
 *
 * <p>动作分类：UPGRADE_DIRECT（直接依赖升版本）/ PIN_TRANSITIVE（传递依赖钉版/排包）/
 * REPLACE（内置已知 EOL 构件对照表命中且无修复版）/ NONE_AVAILABLE（暂无修复版，给缓解建议）。
 * REPLACE 只来自静态对照表（dependency-check 无废弃信号，不臆测）。</p>
 */
public final class RemediationAdvisor {

    public static final String UPGRADE_DIRECT = "UPGRADE_DIRECT";
    public static final String PIN_TRANSITIVE = "PIN_TRANSITIVE";
    public static final String REPLACE = "REPLACE";
    public static final String NONE_AVAILABLE = "NONE_AVAILABLE";

    /** 已知 EOL/废弃构件对照（artifactId 精确匹配），仅作 REPLACE 建议，可后续扩充 */
    private static final Map<String, String> KNOWN_EOL = Map.of(
            "commons-collections", "org.apache.commons:commons-collections4",
            "log4j", "org.apache.logging.log4j:log4j-core（log4j 1.x 已 EOL）");

    private RemediationAdvisor() {
    }

    /**
     * @param groupId/artifactId/currentVersion 构件坐标（均可空）
     * @param versionRanges  detail.versionRanges（cpe/versionStart/EndIncluding/Excluding 各可空）
     * @param directDep      是否直接依赖（null = 依赖树缺失，未知）
     * @param depPath        引入链（直接依赖顶端坐标，传递时非空）
     * @return detail_json.remediation 结构
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> advise(String groupId, String artifactId, String currentVersion,
                                             Object versionRanges, Boolean directDep, String depPath) {
        List<Map<String, String>> ranges = versionRanges instanceof List<?> l
                ? (List<Map<String, String>>) l : List.of();
        String recommended = recommend(currentVersion, ranges);

        Map<String, Object> rem = new LinkedHashMap<>();
        if (recommended != null) {
            rem.put("recommendedVersion", recommended);
        }
        if (!ranges.isEmpty()) {
            rem.put("versionRangesRaw", rawRanges(ranges));
        }
        if (depPath != null && !depPath.isBlank()) {
            rem.put("depPath", depPath);
        }

        if (recommended != null) {
            if (Boolean.TRUE.equals(directDep)) {
                rem.put("action", UPGRADE_DIRECT);
                rem.put("note", "直接依赖：将 " + ga(groupId, artifactId) + " 升级到 " + recommended);
            } else {
                // 传递依赖 或 依赖树缺失未知（保守按 PIN_TRANSITIVE 给两种改法）
                rem.put("action", PIN_TRANSITIVE);
                rem.put("note", "传递依赖：dependencyManagement 钉版 " + ga(groupId, artifactId) + ":" + recommended
                        + "，或对顶端直接依赖" + (depPath != null && !depPath.isBlank() ? " " + depPath : "") + " 排包（exclusion）");
            }
        } else if (artifactId != null && KNOWN_EOL.containsKey(artifactId)) {
            rem.put("action", REPLACE);
            rem.put("note", "构件疑已废弃且无修复版，建议替换为 " + KNOWN_EOL.get(artifactId) + "（人工确认）");
        } else {
            rem.put("action", NONE_AVAILABLE);
            rem.put("note", ranges.isEmpty()
                    ? "NVD 无版本区间数据，推荐版本需人工查证；可先评估缓解措施（网络隔离/WAF 规则），或走「风险接受」抑制"
                    : "区间推导不出安全版本（可能只有 EndIncluding 边界），需人工查证；可先评估缓解措施，或走「风险接受」抑制");
        }
        return rem;
    }

    /**
     * 从版本区间推导最低安全版本：当前版本命中的区间 [start, endExcluding) 的 endExcluding 即修复版候选；
     * 候选内同 major 优先，无则跨 major 取最低。只有 EndIncluding 边界的区间无法可靠推导（下一版本未知）→ 放弃。
     */
    static String recommend(String currentVersion, List<Map<String, String>> ranges) {
        List<String> candidates = new ArrayList<>();
        for (Map<String, String> r : ranges) {
            String endEx = r.get("versionEndExcluding");
            if (endEx == null || endEx.isBlank()) {
                continue;
            }
            String startIn = r.get("versionStartIncluding");
            String startEx = r.get("versionStartExcluding");
            if (currentVersion == null || currentVersion.isBlank()) {
                candidates.add(endEx);
                continue;
            }
            boolean aboveStart = startIn != null ? compareVersions(currentVersion, startIn) >= 0
                    : startEx != null ? compareVersions(currentVersion, startEx) > 0 : true;
            boolean belowEnd = compareVersions(currentVersion, endEx) < 0;
            if (aboveStart && belowEnd) {
                candidates.add(endEx); // 当前版本确在脆弱区间内，endExcluding 是该区间首个修复版
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (currentVersion != null && !currentVersion.isBlank()) {
            String curMajor = majorOf(currentVersion);
            List<String> sameMajor = candidates.stream()
                    .filter(v -> majorOf(v).equals(curMajor))
                    .sorted(RemediationAdvisor::compareVersions)
                    .toList();
            if (!sameMajor.isEmpty()) {
                return sameMajor.get(0);
            }
        }
        return candidates.stream().min(Comparator.comparing(v -> v, RemediationAdvisor::compareVersions)).orElse(null);
    }

    /** 宽松版本比较：按非字母数字切段，数字段按数值比，其余按字典序；无法解析段不抛错 */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[^A-Za-z0-9]+");
        String[] pb = b.split("[^A-Za-z0-9]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int cmp = isDigits(sa) && isDigits(sb)
                    ? Long.compare(parseLongSafe(sa), parseLongSafe(sb))
                    : sa.compareToIgnoreCase(sb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    static String majorOf(String version) {
        int dot = version.indexOf('.');
        return dot > 0 ? version.substring(0, dot) : version;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.length() > 15 ? s.substring(0, 15) : s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static List<String> rawRanges(List<Map<String, String>> ranges) {
        List<String> out = new ArrayList<>();
        for (Map<String, String> r : ranges) {
            StringBuilder sb = new StringBuilder();
            String cpe = r.get("cpe");
            if (cpe != null) {
                sb.append(cpe).append(' ');
            }
            if (r.get("versionStartIncluding") != null) {
                sb.append(">=").append(r.get("versionStartIncluding")).append(' ');
            }
            if (r.get("versionStartExcluding") != null) {
                sb.append(">").append(r.get("versionStartExcluding")).append(' ');
            }
            if (r.get("versionEndIncluding") != null) {
                sb.append("<=").append(r.get("versionEndIncluding")).append(' ');
            }
            if (r.get("versionEndExcluding") != null) {
                sb.append("<").append(r.get("versionEndExcluding"));
            }
            if (!sb.isEmpty()) {
                out.add(sb.toString().strip());
            }
        }
        return out;
    }

    private static String ga(String groupId, String artifactId) {
        return groupId == null ? String.valueOf(artifactId) : groupId + ":" + artifactId;
    }
}
