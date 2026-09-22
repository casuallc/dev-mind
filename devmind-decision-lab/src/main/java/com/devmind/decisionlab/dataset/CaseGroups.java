package com.devmind.decisionlab.dataset;

import com.devmind.common.decision.TriageQuestions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CAP-56 FR-02 对照组词汇表 —— <b>本 CAP 的立身教训所在</b>。
 *
 * <p>2026-09-22 真机实测：{@code multilingual} 在分诊题面上对 {@code duplicate} 三组输入全判「重复」
 * （0.988 / 0.931 / 0.972），其中包括<b>压根没召回到东西</b>和<b>召回的完全不相关</b>两组。
 * 单看准确率，这种退化的数字还挺好看——「恒答重复」在 dupe 占多数的样本上就是高分。
 * 所以基准集<b>必须</b>把这三种情形显式摆进来，否则"模型在测什么"根本说不清。</p>
 *
 * <p><b>标签必须能被内容证伪</b>：只按 case_group 字段统计对照组，等于让标注的人自己声明合规——
 * 打上 {@code EMPTY_RECALL} 却塞了三条召回结果，统计上照样算"对照组齐备"。所以
 * {@link #holds} 会真去读 state：空召回组的 {@code similar_entries} 必须逐字是
 * {@link TriageQuestions#EMPTY_RECALL}，逐字重复组必须真含提案正文，不相关组必须真不含。
 * 冻结时逐条验，对不上就<b>拒绝冻结</b>。</p>
 */
public final class CaseGroups {

    /** 普通样本（不是对照组，是评测集的主体） */
    public static final String NORMAL = "NORMAL";
    /** 对照①：库里没有相似条目——正确答案必然是"不重复" */
    public static final String EMPTY_RECALL = "EMPTY_RECALL";
    /** 对照②：召回条目与提案正文逐字相同——正确答案必然是"重复" */
    public static final String VERBATIM_DUP = "VERBATIM_DUP";
    /** 对照③：召回条目明显不相干——正确答案必然是"不重复" */
    public static final String IRRELEVANT = "IRRELEVANT";

    public static final List<String> ALL = List.of(NORMAL, EMPTY_RECALL, VERBATIM_DUP, IRRELEVANT);

    /** FR-02 红线：这三个组缺一不可，冻结时强制 */
    public static final List<String> CONTROL = List.of(EMPTY_RECALL, VERBATIM_DUP, IRRELEVANT);

    /** 对照组判据里要比对的两个 state 键（与 {@code KnowledgeTriageService.buildState} 同源） */
    public static final String SIMILAR_KEY = "similar_entries";
    public static final String CONTENT_KEY = "proposal_content";

    /** 逐字比对要求正文达到这个长度：太短的正文（"1"、"是"）会碰巧出现在任何条目里，比对没意义 */
    static final int MIN_CONTENT_CHARS = 4;

    private CaseGroups() {
    }

    /**
     * 规范化用户给的 case_group：空 → {@link #NORMAL}；认不出 → null（调用方报 400）。
     *
     * <p><b>认不出必须报错，不能悄悄当 NORMAL</b>：拼错一个字母（{@code EMPTY_RECAL}）若被吞成
     * 普通样本，对照组红线就被无声地绕过了——统计上"对照组齐备"永远不成立，但也没人知道为什么。
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String v = raw.trim().toUpperCase();
        return ALL.contains(v) ? v : null;
    }

    public static boolean isControl(String group) {
        return CONTROL.contains(group);
    }

    /** 中文标签（列表/报告上用；机器值另存） */
    public static String label(String group) {
        return switch (group == null ? NORMAL : group) {
            case EMPTY_RECALL -> "对照·空召回";
            case VERBATIM_DUP -> "对照·逐字重复";
            case IRRELEVANT -> "对照·不相关";
            default -> "普通";
        };
    }

    /**
     * 校验某条 state 是否<b>真的是</b>它声明的那个对照组。
     *
     * @return 空 = 成立；非空 = 一句话说明为什么不成立（直接给用户看，所以要说清怎么改）
     */
    public static Optional<String> holds(String group, Map<String, Object> state) {
        String g = group == null ? NORMAL : group;
        if (NORMAL.equals(g)) {
            return Optional.empty();
        }
        String entries = text(state == null ? null : state.get(SIMILAR_KEY));
        String content = flatten(text(state == null ? null : state.get(CONTENT_KEY)));
        return switch (g) {
            case EMPTY_RECALL -> entries.strip().startsWith(TriageQuestions.EMPTY_RECALL)
                    ? Optional.empty()
                    : Optional.of("对照·空召回要求 " + SIMILAR_KEY + " 就是「" + TriageQuestions.EMPTY_RECALL
                            + "」（可后缀检索降级说明），实际不是——空召回被填了内容就不再是对照组");
            case VERBATIM_DUP -> {
                if (content.length() < MIN_CONTENT_CHARS) {
                    yield Optional.of("对照·逐字重复要在 " + CONTENT_KEY + " 里给出正文（至少 "
                            + MIN_CONTENT_CHARS + " 字），否则无从判断是否逐字重复");
                }
                yield flatten(entries).contains(content)
                        ? Optional.empty()
                        : Optional.of("对照·逐字重复要求 " + SIMILAR_KEY + " 中有一条与 " + CONTENT_KEY
                                + " 逐字相同（忽略空白），实际没有——请把提案正文原样粘进召回条目");
            }
            case IRRELEVANT -> {
                if (content.length() < MIN_CONTENT_CHARS) {
                    yield Optional.of("对照·不相关要在 " + CONTENT_KEY + " 里给出正文（至少 "
                            + MIN_CONTENT_CHARS + " 字），否则无从判断是否不相关");
                }
                yield flatten(entries).contains(content)
                        ? Optional.of("对照·不相关要求 " + SIMILAR_KEY + " 中<b>不</b>含 " + CONTENT_KEY
                                + " 的正文，实际含了——这样就变成逐字重复组了")
                        : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /**
     * 冻结时逐条验对照组：返回 {@code 条目 id → 失败原因}（空 map = 全过）。
     * 键用条目 id 是为了让界面能直接定位到那一行，而不是给一句"对照组不合法"。
     */
    public static Map<Long, String> violations(List<ItemCase> items) {
        Map<Long, String> bad = new LinkedHashMap<>();
        for (ItemCase item : items) {
            holds(item.caseGroup(), item.state()).ifPresent(reason -> bad.put(item.id(), reason));
        }
        return bad;
    }

    /** 归一化：空白压成单个空格并去首尾——CJK 正文里的换行/缩进差异不该让"逐字相同"判否 */
    static String flatten(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** {@link #violations} 的输入（只取校验要用的两样，避免把实体拖进这个纯词汇表） */
    public record ItemCase(Long id, String caseGroup, Map<String, Object> state) {
    }
}
