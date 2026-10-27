package com.devmind.worklog.service;

import com.devmind.worklog.dto.SuggestedObjective;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-93 FR-02 OKR 草稿产物解析：把 okr-suggest-<period>.md 的两级列表
 * （契约 `- [O] 标题（权重 40%）（父O#id）` / 缩进 `- [KR] 标题（权重 30%）`——父O 标注
 * 可选且仅 QUARTER scope 有意义；见 {@link WorklogAssetSeeder#OKR_SUGGEST_SKELETON}）
 * 解析为建议目标树。容错口径同 {@link PlanSuggestParser}：不合规行静默丢弃、括号段
 * 全/半角兼容、围栏代码块内的行不算、KR 无前置 O 丢弃、标题截 256、权重越界置空，
 * 总量封顶 {@value #MAX_OBJECTIVES} 个 O、每 O {@value #MAX_KRS} 条 KR。
 */
final class OkrSuggestParser {

    /** 单次解析上限（防爆：模型失控也不往草稿灌几百条） */
    static final int MAX_OBJECTIVES = 5;
    static final int MAX_KRS = 6;

    private static final int TITLE_CHARS = 256;

    /** O 行：`- [O] 标题（权重 40%）（父O#12）`——权重/父O 段各自可缺、可任意顺序收尾 */
    private static final Pattern O_LINE = Pattern.compile(
            "^\\s*[-*]\\s*\\[[Oo]]\\s*(.+?)\\s*"
                    + "(?:[（(]\\s*权重\\s*(\\d{1,3})\\s*%\\s*[）)])?\\s*"
                    + "(?:[（(]\\s*父[Oo]#\\s*(\\d+)\\s*[）)])?\\s*$");

    /** KR 行：缩进的 `- [KR] 标题（权重 30%）`（行首必须有空白——与 O 行分级） */
    private static final Pattern KR_LINE = Pattern.compile(
            "^\\s+[-*]\\s*\\[[Kk][Rr]]\\s*(.+?)\\s*"
                    + "(?:[（(]\\s*权重\\s*(\\d{1,3})\\s*%\\s*[）)])?\\s*$");

    private OkrSuggestParser() {
    }

    static List<SuggestedObjective> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<SuggestedObjective> result = new ArrayList<>();
        List<SuggestedObjective.SuggestedKr> currentKrs = null;
        boolean inFence = false;
        for (String raw : content.split("\\R")) {
            if (result.size() >= MAX_OBJECTIVES && currentKrs != null
                    && currentKrs.size() >= MAX_KRS) {
                break; // 全部封顶
            }
            String line = raw.strip();
            if (line.startsWith("```")) {
                inFence = !inFence; // 围栏内的列表行只是示例文本，不算建议
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher km = KR_LINE.matcher(raw);
            if (km.matches() && !raw.startsWith("-") && !raw.startsWith("*")) {
                if (currentKrs == null || currentKrs.size() >= MAX_KRS) {
                    continue; // KR 无前置 O / 该 O 的 KR 已封顶 → 丢弃
                }
                currentKrs.add(new SuggestedObjective.SuggestedKr(
                        title(km.group(1)), null, weight(km.group(2))));
                continue;
            }
            Matcher om = O_LINE.matcher(raw);
            if (om.matches()) {
                if (result.size() >= MAX_OBJECTIVES) {
                    continue;
                }
                currentKrs = new ArrayList<>();
                result.add(new SuggestedObjective(title(om.group(1)), null, weight(om.group(2)),
                        parentId(om.group(3)), currentKrs));
                continue;
            }
            // 非列表行（标题/解释文字）：静默忽略，不打断当前 O 的 KR 归属
        }
        return result;
    }

    private static String title(String raw) {
        String t = raw == null ? "" : raw.strip();
        return t.length() <= TITLE_CHARS ? t : t.substring(0, TITLE_CHARS);
    }

    /** 权重：0~100 合法，越界/缺省置 null（可空字段不编造）。 */
    private static Integer weight(String raw) {
        if (raw == null) {
            return null;
        }
        int w = Integer.parseInt(raw);
        return w >= 0 && w <= 100 ? w : null;
    }

    private static Long parentId(String raw) {
        return raw == null ? null : Long.valueOf(raw);
    }
}
