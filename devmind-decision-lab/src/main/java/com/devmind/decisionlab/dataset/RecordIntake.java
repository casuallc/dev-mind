package com.devmind.decisionlab.dataset;

import com.devmind.common.decision.TriageQuestions;
import com.devmind.decision.record.GoldDistributions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CAP-56 FR-02 「从决策记录收编」的逐条判定 —— <b>纯函数，不碰库、不碰 JSON 解析</b>。
 *
 * <p>回流（{@code decision_records} → 评测样本）是 CAP-55 数据飞轮的下半圈：真实提案的真实裁决，
 * 是唯一不需要人为构造的样本来源。但它<b>不能照单全收</b>，而每条拒收都必须说得出理由——
 * "收编了 37 条"旁边如果没人知道那 200 条为什么不收，这个数字就没法判断。</p>
 *
 * <p>拒收的四种情形（{@link #REASON_LABELS}）各自的处置完全不同，所以分开报而不是合成一句
 * "样本不合法"：没裁决过 → 去催裁决；题面不同 → 那份记录是旧题面测的，别混进来；
 * gold 落不上题面 → 人工动作不构成某道题的答案（如"拒绝提案"）；已收编过 → 不用管。</p>
 *
 * <p><b>对照组靠内容自动识别，但只认能证伪的两个</b>：空召回（{@code similar_entries}
 * 逐字是占位文案）与逐字重复（正文逐字出现在召回里），判据就是冻结时用的
 * {@link CaseGroups#holds}——同一把尺子，所以不会出现"收编时算对照组、冻结时不算"。
 * {@code IRRELEVANT} <b>故意不自动识别</b>：判断"召回条目明显不相干"是人的事，
 * 靠"正文不在召回里"来推断的话，每一条普通样本都会变成"不相关对照组"，
 * 那个组就再也说明不了任何事情。</p>
 */
final class RecordIntake {

    /** 缺 state/questions/gold 之一（只有模型建议、还没人裁决的记录就是这个下场） */
    static final String REASON_SNAPSHOT = "SNAPSHOT_INCOMPLETE";
    /** 题面与当前标准题面不同（旧题面测的记录） */
    static final String REASON_QUESTION_SET = "QUESTION_SET_MISMATCH";
    /** gold 落不上题面：人工动作不构成任何一道题的答案 */
    static final String REASON_GOLD = "GOLD_NOT_ON_QUESTION_SET";
    /** 本集已经收编过这条记录（重复收编不是错，只是无事可做） */
    static final String REASON_COLLECTED = "ALREADY_COLLECTED";

    /**
     * 报告里的展示顺序：从"最该先处理的"（去催裁决、去看那份旧题面记录）到"无事可做"（已收编过）。
     * 计数表按它建（每个原因都在表里，缺的记 0——"0 条题面不同"本身就是有用的一条信息）。
     */
    static final List<String> REASON_ORDER =
            List.of(REASON_SNAPSHOT, REASON_QUESTION_SET, REASON_GOLD, REASON_COLLECTED);

    /** 单次收编最多扫多少条：几百条是常态，几万条要人先按能力/时间收窄（不静默截断，见 preview 的 truncated） */
    static final int MAX_SCAN = 2000;

    private RecordIntake() {
    }

    public static String label(String reasonCode) {
        return switch (reasonCode == null ? "" : reasonCode) {
            case REASON_SNAPSHOT -> "缺快照或没有人工裁决（只有模型建议的记录收不了）";
            case REASON_QUESTION_SET -> "题面与当前标准题面不同（旧题面测出来的记录不混进来）";
            case REASON_GOLD -> "人工动作落不上题面（不构成任何一道题的答案）";
            case REASON_COLLECTED -> "本集已收编过";
            default -> reasonCode;
        };
    }

    /**
     * 逐条判定一条记录的<b>内容</b>是否可收编（"是否已收编"由调用方判——那要查库）。
     *
     * @param state     从 {@code state_json} 解析出的快照（解析失败传空 map，这里会判成缺快照）
     * @param questions 从 {@code questions_json} 解析出的题面
     * @param gold      从 {@code gold_json} 解析出的人工原值
     * @return 可收编时 {@link Disposition#collectable()} 为真，并带上要落库的四样
     */
    static Disposition classify(Map<String, Object> state,
                                Map<String, Map<String, Object>> questions,
                                Map<String, Object> gold) {
        if (state == null || state.isEmpty() || questions == null || questions.isEmpty()
                || gold == null || gold.isEmpty()) {
            return Disposition.skip(REASON_SNAPSHOT, null);
        }
        List<String> diff = QuestionSets.diff(questions);
        if (!diff.isEmpty()) {
            return Disposition.skip(REASON_QUESTION_SET, String.join("；", diff));
        }
        Map<String, Object> distributions = GoldDistributions.of(questions, gold);
        if (distributions.isEmpty()) {
            return Disposition.skip(REASON_GOLD,
                    "gold 给的是 " + gold.keySet() + "，题面选项里落不上");
        }
        return Disposition.collect(state, questions, gold, detectCaseGroup(state));
    }

    /** 内容能证伪的对照组自动打标（空召回 / 逐字重复）；其余一律普通样本 */
    static String detectCaseGroup(Map<String, Object> state) {
        for (String group : List.of(CaseGroups.EMPTY_RECALL, CaseGroups.VERBATIM_DUP)) {
            Optional<String> issue = CaseGroups.holds(group, state);
            if (issue.isEmpty()) {
                return group;
            }
        }
        return CaseGroups.NORMAL;
    }

    /**
     * 一条记录的判定结果。
     *
     * @param reasonCode 拒收原因码（可收编时为 null）
     * @param detail     补充说明（如题面差在哪一题），可空
     */
    record Disposition(String reasonCode, String detail, Map<String, Object> state,
                       Map<String, Map<String, Object>> questions, Map<String, Object> gold,
                       String caseGroup) {

        static Disposition skip(String reasonCode, String detail) {
            return new Disposition(reasonCode, detail, null, null, null, null);
        }

        static Disposition collect(Map<String, Object> state, Map<String, Map<String, Object>> questions,
                                   Map<String, Object> gold, String caseGroup) {
            // 题面已逐项等于标准题面；拷一份可变副本，下游（序列化/落库）不再碰调用方的解析结果
            return new Disposition(null, null, new LinkedHashMap<>(state), questions,
                    new LinkedHashMap<>(gold), caseGroup);
        }

        boolean collectable() {
            return reasonCode == null;
        }
    }
}
