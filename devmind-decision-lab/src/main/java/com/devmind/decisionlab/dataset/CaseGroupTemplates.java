package com.devmind.decisionlab.dataset;

import com.devmind.common.decision.TriageQuestions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-56 FR-02 对照组模板：把"造一个对照组"从**规矩**变成**填空**。
 *
 * <p>为什么值得专门做模板：对照组是本 CAP 的红线，但纯靠规则（"必须有三类对照组"）时，
 * 标注的人得自己记住每组要求什么样的 state、正确的 gold 是什么，记错一处整组就白标了。
 * 模板把<b>由构造决定的那部分</b>替他填好——空召回组的 {@code similar_entries} 就是那句占位文案，
 * 逐字重复/不相关组的 {@code duplicate} gold 分别是"是/否"（这正是构造决定的，不需要人判断）。
 * 剩下的（正文、层级、质量分）必须人写，模板故意留空并把它们列进
 * {@link Template#annotateQuestions}——<b>模板不该假装知道它不知道的事</b>。</p>
 *
 * <p>模板给的是 JSON 骨架而不是"最终答案"：{@code state} 里的占位说明（带【】的那些）要让人替换成
 * 真实内容，替换前的骨架拿去冻结是会被 {@link CaseGroups#holds} 拒绝的——这正是期望行为。</p>
 */
public final class CaseGroupTemplates {

    private static final String TITLE = "proposal_title";
    private static final String CONTENT = CaseGroups.CONTENT_KEY;
    private static final String PROJECT = "project";
    private static final String SIMILAR = CaseGroups.SIMILAR_KEY;

    private CaseGroupTemplates() {
    }

    /** 一个对照组的空白骨架（{@code gold} 只含构造决定的那几题，其余待人标） */
    public record Template(String caseGroup,
                           String label,
                           String hint,
                           Map<String, Object> state,
                           Map<String, Map<String, Object>> questions,
                           Map<String, Object> gold,
                           List<String> annotateQuestions) {
    }

    public static List<Template> all() {
        return List.of(emptyRecall(), verbatimDuplicate(), irrelevant());
    }

    /**
     * 对照①空召回：库里什么都没有，正确答案只能是"不重复"。
     * gold 只预填 {@code duplicate=false} —— 这是个是非题，"没东西可比"就直接决定了答案。
     */
    private static Template emptyRecall() {
        return new Template(CaseGroups.EMPTY_RECALL, CaseGroups.label(CaseGroups.EMPTY_RECALL),
                "先把一组已经收录、且与库里既有条目都不重复的经验提案写进来；"
                        + SIMILAR + " 保持这句占位文案不动。这条样本问的是："
                        + "面对空召回，模型还敢不敢说「重复」。",
                state(TriageQuestions.EMPTY_RECALL),
                TriageQuestions.standard(),
                gold(Map.of(TriageQuestions.Q_DUPLICATE, false)),
                List.of(TriageQuestions.Q_LAYER, TriageQuestions.Q_QUALITY));
    }

    /**
     * 对照②逐字重复：召回条目与提案正文一字不差。
     * 标这条样本时把 {@link #CONTENT} 的正文原样粘进 {@link #SIMILAR}（可加条目前的序号与书名号，
     * 只要正文那一段逐字在）。
     */
    private static Template verbatimDuplicate() {
        return new Template(CaseGroups.VERBATIM_DUP, CaseGroups.label(CaseGroups.VERBATIM_DUP),
                "写一条提案正文，再把<b>同一段正文逐字</b>粘进 " + SIMILAR + "（前面可带"
                        + "「1. 《条目名》」这类前缀）。这条样本问的是：一模一样的东西，模型认不认得出来。",
                state("1. 《已有经验》\n【把 " + CONTENT + " 的正文逐字粘到这里】"),
                TriageQuestions.standard(),
                gold(Map.of(TriageQuestions.Q_DUPLICATE, true)),
                List.of(TriageQuestions.Q_LAYER, TriageQuestions.Q_QUALITY));
    }

    /**
     * 对照③不相关：召回条目看着像但讲的不是一回事。
     * 与逐字重复组配对使用——两组 gold 相反，模型若两组都给同一个答案，报告里立刻现形。
     */
    private static Template irrelevant() {
        return new Template(CaseGroups.IRRELEVANT, CaseGroups.label(CaseGroups.IRRELEVANT),
                "写一条提案正文，再往 " + SIMILAR + " 里放一条<b>同领域但不相干</b>的条目"
                        + "（比如都讲部署，但一个讲 Windows 换行、一个讲容器网络）。"
                        + "与逐字重复组配对：模型若两组都答「重复」，报告里立刻现形。",
                state("1. 《相关领域的另一件事》\n【一条与 " + CONTENT + " 无关的条目内容】"),
                TriageQuestions.standard(),
                gold(Map.of(TriageQuestions.Q_DUPLICATE, false)),
                List.of(TriageQuestions.Q_LAYER, TriageQuestions.Q_QUALITY));
    }

    private static Map<String, Object> state(String similarEntries) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(TITLE, "");
        state.put(CONTENT, "");
        state.put(PROJECT, "");
        state.put(SIMILAR, similarEntries);
        return state;
    }

    private static Map<String, Object> gold(Map<String, Object> entries) {
        return new LinkedHashMap<>(entries);
    }
}
