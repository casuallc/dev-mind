package com.devmind.worklog.service;

import com.devmind.worklog.dto.SuggestedPlanItem;
import com.devmind.worklog.model.WorklogEntryEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-88 M3 FR-10「AI 建议计划」产物解析：把 plan-suggest-yyyy-Www.md 的逐行建议
 * （契约 `- [类型] 标题（预估 Xh）（KR#id）`——KR 标注可选，CAP-91 M3 FR-11；
 * 见 {@link WorklogAssetSeeder#PLAN_SUGGEST_SKELETON}）解析为建议项。容错口径：不合规行静默丢弃（模型偶尔带标题/解释行），标题截 256，
 * 类型不识别回退 DEV，预估只认 `数字+h`（0.25 步进），总量封顶 {@value #MAX_ITEMS} 条。
 */
final class PlanSuggestParser {

    /** 单次解析上限（防爆：模型失控也不往库里灌几百条） */
    static final int MAX_ITEMS = 12;

    private static final int TITLE_CHARS = 256;
    private static final Set<String> TYPES = Set.of(
            WorklogEntryEntity.TYPE_DEV, WorklogEntryEntity.TYPE_SUPPORT, WorklogEntryEntity.TYPE_MEETING,
            WorklogEntryEntity.TYPE_RESEARCH, WorklogEntryEntity.TYPE_OTHER);

    /**
     * 建议行：`- [类型] 标题（预估 2h）（KR#12）`——类型段、预估段、KR 标注（CAP-91 M3 FR-11）
     * 各自可缺；预估/KR 括号兼容全/半角，预估单位只认 h（小时），数字允许小数；
     * KR 标注固定收尾（先预估后 KR 或只写其一均可）。
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*[-*]\\s*(?:\\[([A-Za-z]+)]\\s*)?(.+?)\\s*"
                    + "(?:[（(]\\s*预估\\s*([0-9]+(?:\\.[0-9]+)?)\\s*[hH]\\s*[）)])?\\s*"
                    + "(?:[（(]\\s*[Kk][Rr]#\\s*(\\d+)\\s*[）)])?\\s*$");

    private PlanSuggestParser() {
    }

    static List<SuggestedPlanItem> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<SuggestedPlanItem> items = new ArrayList<>();
        boolean inFence = false;
        for (String raw : content.split("\\R")) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            String line = raw.strip();
            if (line.startsWith("```")) {
                inFence = !inFence; // 围栏内的列表行只是示例文本，不算建议
                continue;
            }
            if (inFence || (!line.startsWith("-") && !line.startsWith("*"))) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String title = m.group(2).strip();
            // 剥掉行尾残留的 markdown 强调/标点噪音
            title = title.replaceAll("[`*_]+$", "").strip();
            if (title.isEmpty() || title.length() < 2) {
                continue;
            }
            if (title.length() > TITLE_CHARS) {
                title = title.substring(0, TITLE_CHARS);
            }
            String type = m.group(1) == null ? WorklogEntryEntity.TYPE_DEV : m.group(1).toUpperCase();
            if (!TYPES.contains(type)) {
                type = WorklogEntryEntity.TYPE_DEV;
            }
            Integer minutes = null;
            if (m.group(3) != null) {
                try {
                    minutes = WorklogEntryFile.toMinutes(Double.parseDouble(m.group(3)));
                } catch (RuntimeException ignored) {
                    // 预估解析失败按未填处理（建议项预估可有可无）
                }
            }
            Long krId = null;
            if (m.group(4) != null) {
                try {
                    krId = Long.parseLong(m.group(4));
                } catch (RuntimeException ignored) {
                    // KR id 超范围按未标注处理（归属校验在 insertSuggestions）
                }
            }
            items.add(new SuggestedPlanItem(title, type, minutes, krId));
        }
        return items;
    }
}
