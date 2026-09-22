package com.devmind.decision.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * CAP-55 FR-05 人工裁决 → 训练 gold 的换算：把"人选了什么"按题面 criteria 摊成分布。
 *
 * <p><b>为什么按 criteria 摊而不是把人给的原值直接塞进去</b>：训练目标是"给定题面时模型该输出
 * 什么分布"，选项集合由题面决定。人给的值若不在题面选项里（比如题面改了、旧记录用新题面导出），
 * 硬塞会造出一个模型从未见过的标签——那种样本比没有更糟。<b>落不上就丢这一题</b>，
 * 整个 gold 都落不上则该行不进导出（{@link #of} 返回空 map，调用方据此跳过）。</p>
 *
 * <p>三种原语的换算口径：
 * <ul>
 *   <li>{@code choice}：criteria 是 {@code {选项名: 说明}}，分布键 = 选项名，选中项 1.0 其余 0.0；</li>
 *   <li>{@code score}：criteria 是等级列表，分布键 = 等级下标（"0"/"1"/…，与模型
 *       {@code probabilities} 同域），人工值取整后落位；</li>
 *   <li>{@code noul}：模型原生输出就是一个概率，故 gold 也只用<b>单值</b> {@code {"noul":0/1}}
 *       ——不接受 0/1 之外的"部分概率"当作人工标签，&gt;=0.5 记 1。</li>
 * </ul>
 */
public final class GoldDistributions {

    /** noul 的单值键（与 laya 应答里 noul 答案的字段名一致） */
    static final String NOUL_KEY = "noul";

    private GoldDistributions() {
    }

    /**
     * 逐题换算人工裁决：题面里有、人能答上、且值落在选项内的题才产出分布。
     *
     * @return 题 id → 分布（题面顺序）；落不上任何一题时为空 map（该行不可训练）
     */
    public static Map<String, Object> of(Map<String, Map<String, Object>> questions, Map<String, Object> gold) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (questions == null || questions.isEmpty() || gold == null || gold.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Map<String, Object>> entry : questions.entrySet()) {
            Object value = gold.get(entry.getKey());
            if (value == null) {
                continue;
            }
            Map<String, Object> question = entry.getValue();
            Map<String, Object> distribution = question == null ? null
                    : distribution(text(question.get("type")), question.get("criteria"), value);
            if (distribution != null) {
                out.put(entry.getKey(), distribution);
            }
        }
        return out;
    }

    /** 单题换算；题面缺 criteria 或值落不上 → null（该题不进 gold） */
    public static Map<String, Object> distribution(String type, Object criteria, Object gold) {
        if (type == null || gold == null) {
            return null;
        }
        return switch (type) {
            case "choice" -> oneHot(asChoiceKeys(criteria), String.valueOf(gold));
            case "score" -> {
                List<String> keys = asScoreKeys(criteria);
                yield keys.isEmpty() ? null : oneHot(keys, asIndex(gold, keys.size()));
            }
            case "noul" -> {
                Boolean yes = asYesNo(gold);
                if (yes == null) {
                    yield null;
                }
                Map<String, Object> single = new LinkedHashMap<>();
                single.put(NOUL_KEY, yes ? 1.0 : 0.0);
                yield single;
            }
            default -> null;
        };
    }

    private static Map<String, Object> oneHot(List<String> keys, String hit) {
        if (keys.isEmpty() || hit == null || !keys.contains(hit)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, key.equals(hit) ? 1.0 : 0.0);
        }
        return out;
    }

    /** choice 题面：criteria 是 {选项名: 说明}，取键序 */
    private static List<String> asChoiceKeys(Object criteria) {
        if (!(criteria instanceof Map<?, ?> map)) {
            return List.of();
        }
        return map.keySet().stream().map(String::valueOf).toList();
    }

    /** score 题面：criteria 是等级列表，分布键是下标字符串 */
    private static List<String> asScoreKeys(Object criteria) {
        if (!(criteria instanceof List<?> levels)) {
            return List.of();
        }
        return IntStream.range(0, levels.size()).mapToObj(String::valueOf).toList();
    }

    /** 人工值 → 等级下标字符串；越界/非数值 → null */
    private static String asIndex(Object gold, int levels) {
        Double numeric = null;
        if (gold instanceof Number n) {
            numeric = n.doubleValue();
        } else if (gold instanceof String s) {
            try {
                numeric = Double.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (numeric == null) {
            return null;
        }
        int idx = (int) Math.round(numeric);
        return idx < 0 || idx >= levels ? null : String.valueOf(idx);
    }

    /** 人工值 → 是/否；noul 的人工标签就是"是不是"，非 0/1 的概率按 >=0.5 取整 */
    private static Boolean asYesNo(Object gold) {
        if (gold instanceof Boolean b) {
            return b;
        }
        if (gold instanceof Number n) {
            return n.doubleValue() >= 0.5;
        }
        if (gold instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return true;
            }
            if ("false".equalsIgnoreCase(t)) {
                return false;
            }
            try {
                return Double.parseDouble(t) >= 0.5;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
