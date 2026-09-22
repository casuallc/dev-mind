package com.devmind.decisionlab.eval;

import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-56 评测报告的读法（服务端侧的口径）。
 *
 * <p><b>这是与 {@code tools/laya-sidecar/lab/laya_eval.py} 的字段契约</b>，键名两边必须一致：
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "checkpoint": {"slot": "typed-decisions", "path": "…", "kind": "BASE"},
 *   "dataset":    {"id": 7, "name": "基准集", "version": 2,
 *                  "questionSetVersion": "TriageQuestions@1", "itemCount": 60},
 *   "metrics":    {"items": 60,
 *                  "choice": {"accuracy": 0.72, "softAccuracy": 0.75, "brier": 0.31},
 *                  "score":  {"mae": 0.44, "within1Level": 0.9},
 *                  "noul":   {"rate": 0.05},
 *                  "latencyMs": {"p50": 12.0, "p95": 30.0}},
 *   "baselines":  {"random": 0.33, "majority": 0.46},          ← FR-03：必须始终报
 *   "byCaseGroup": [{"caseGroup": "EMPTY_RECALL", "items": 10, "accuracy": 0.1}],
 *   "perItem":    [{"id": 3, "caseGroup": "NORMAL", "question": "duplicate",
 *                   "gold": "采纳", "pred": "重复", "correct": false,
 *                   "confidence": 0.99, "latencyMs": 11.2}],
 *   "compare":    {"baselineCheckpoint": "english", "win": 12, "lose": 30, "tie": 18},
 *   "calibration":{"mode": "heldout", "before": {"ece": 0.21}, "after": {"ece": 0.06},
 *                  "temperature": {"duplicate|3": 1.4}}
 * }
 * </pre>
 *
 * <p><b>服务端不重算指标</b>：指标由脚本用 {@code laya} 官方原语（{@code ece_score} 等）算出，
 * 服务端只做两件事——检查必报项在不在（FR-03 的基线是硬要求），以及把列表页要显示的几个
 * 数字摘出来。重算一遍等于把口径变成两份，而这两份迟早会不一致。</p>
 *
 * <p><b>缺项不编数</b>：解析不出来的键一律当"没有"（页面上显示为 — 而不是 0），
 * 因为 0 是一个真实的指标值：准确率 0 与"没测出来"必须是两回事。</p>
 */
public final class EvalReport {

    /** 报告结构版本（脚本与平台协商用；比本类支持的新时不解析，报 INCOMPLETE） */
    public static final int SCHEMA_VERSION = 1;

    private EvalReport() {
    }

    /** 必报项检查（metrics 与 baselines 是 FR-03 的硬要求） */
    public static String status(Map<String, Object> report) {
        if (report == null) {
            return DecisionEvaluationEntity.REPORT_MISSING;
        }
        Object schema = report.get("schemaVersion");
        if (schema instanceof Number n && n.intValue() > SCHEMA_VERSION) {
            return DecisionEvaluationEntity.REPORT_INCOMPLETE;
        }
        if (!(report.get("metrics") instanceof Map<?, ?>) || !(report.get("baselines") instanceof Map<?, ?>)) {
            return DecisionEvaluationEntity.REPORT_INCOMPLETE;
        }
        return DecisionEvaluationEntity.REPORT_OK;
    }

    /**
     * 列表页头条：准确率 / 两条基线 / 校准后 ECE / 逐题胜负 / 条数。
     * 只放小数字，不放逐题明细（列表页一页 20 行的渲染不该解析 20 份大报告）。
     */
    public static Map<String, Object> headline(Map<String, Object> report) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("accuracy", num(report, "metrics", "choice", "accuracy"));
        h.put("softAccuracy", num(report, "metrics", "choice", "softAccuracy"));
        h.put("brier", num(report, "metrics", "choice", "brier"));
        h.put("mae", num(report, "metrics", "score", "mae"));
        h.put("items", num(report, "metrics", "items"));
        h.put("random", num(report, "baselines", "random"));
        h.put("majority", num(report, "baselines", "majority"));
        h.put("eceAfter", num(report, "calibration", "after", "ece"));
        h.put("eceBefore", num(report, "calibration", "before", "ece"));
        h.put("win", num(report, "compare", "win"));
        h.put("lose", num(report, "compare", "lose"));
        h.put("tie", num(report, "compare", "tie"));
        return h;
    }

    /** 报告里的条目数；缺了就退回触发时快照的条数（两者不同说明有样本被跳过） */
    public static int itemCount(Map<String, Object> report, int fallback) {
        Object v = num(report, "metrics", "items");
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /** 对照组分解（{@code byCaseGroup}），无则空列表 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> byCaseGroup(Map<String, Object> report) {
        if (report == null || !(report.get("byCaseGroup") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .toList();
    }

    /**
     * 日志收口那一行人读摘要（跑完时打给日志，也是通知文案的来源）。
     * 缺失的项直接不写——写 "准确率 —" 不如不写。
     */
    public static String summary(Map<String, Object> report) {
        if (report == null) {
            return "未产出报告（脚本没有打印 DEVMIND_REPORT 行）";
        }
        Map<String, Object> h = headline(report);
        StringBuilder sb = new StringBuilder("评测结果：");
        boolean any = false;
        any |= append(sb, any, "准确率", h.get("accuracy"));
        any |= append(sb, any, "随机基线", h.get("random"));
        any |= append(sb, any, "多数类基线", h.get("majority"));
        any |= append(sb, any, "样本", h.get("items"));
        Object win = h.get("win");
        Object lose = h.get("lose");
        if (win instanceof Number) {
            sb.append(any ? "，" : "").append("对照胜/负/平 ").append(fmt(win)).append("/")
                    .append(fmt(lose)).append("/").append(fmt(h.get("tie")));
            any = true;
        }
        return any ? sb.toString() : "评测结果：报告里没有可展示的指标";
    }

    private static boolean append(StringBuilder sb, boolean any, String label, Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        sb.append(any ? "，" : "").append(label).append(' ').append(fmt(value));
        return true;
    }

    private static String fmt(Object value) {
        if (!(value instanceof Number n)) {
            return "—";
        }
        double d = n.doubleValue();
        // 0/1 之间的比率保留三位；条数等整数原样打
        if (d == Math.rint(d) && Math.abs(d) >= 1) {
            return String.valueOf((long) d);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    /** 按路径取值；任一层缺失或不是 Map = null */
    private static Object num(Map<String, Object> report, String... path) {
        Object cur = report;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(key);
        }
        return cur instanceof Number ? cur : null;
    }
}
