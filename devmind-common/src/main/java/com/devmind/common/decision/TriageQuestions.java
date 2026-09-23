package com.devmind.common.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-55 FR-04 分诊题面（laya 原生 schema：{@code type}/{@code instructions}/{@code criteria}）。
 *
 * <p><b>题面是代码常量，不做成配置</b>：换题面等于换训练目标——存量 decision_records 里的
 * gold 立刻与新题面错位（选项 key 没了、等级数变了）。要改就该像改接口一样走评审，
 * 而不是让运维在界面上随手改。题 id 一经发布不得改名：decision_records 按题 id 对答案。</p>
 *
 * <p><b>机器值与展示文案分开</b>：选项 key（{@code global|project|discard}）与等级下标
 * 会一路存进 triage_json 与训练集，是数据的一部分；中文短标签（{@link #layerLabel}/
 * {@link #qualityLabel}）只服务于界面。合成一个字段的话，"改一句文案"就会让历史数据对不上。</p>
 *
 * <p>laya 的 criteria 口径：choice 是 {@code {key: 说明}}（答案是 key），
 * score 是<b>列表</b>（下标即分值，"标签：说明" 写成一条便于模型理解）。</p>
 *
 * <p><b>为什么住在 common（CAP-56 §3.1 上提）</b>：题面是<b>模型契约</b>而非知识库的内部细节——
 * 三处共用同一份：① 消费方分诊时发给模型；② CAP-56 评测时算指标要对齐 gold；③ CAP-56 微调时
 * 它是训练目标。留在 devmind-knowledge 会让评测/微调模块只能反向依赖知识库。上提后
 * {@code common.decision} 是题面、答案（{@link DecisionAnswer}）、引擎（{@link DecisionEngine}）
 * 三者的共同住处。</p>
 */
public final class TriageQuestions {

    /** 采纳层级（choice）：取值与 adopt API 的 target 同域 */
    public static final String Q_LAYER = "adopt_layer";
    /** 重复风险（noul）：state 里的 {@code similar_entries} 是判据 */
    public static final String Q_DUPLICATE = "duplicate";
    /** 质量分（score）：三级 */
    public static final String Q_QUALITY = "quality";

    /** 重复判定的召回条数（CAP-55 §FR-04：top3 相似条目拼进 state） */
    public static final int SIMILAR_TOP_K = 3;

    public static final String LAYER_GLOBAL = "global";
    public static final String LAYER_PROJECT = "project";
    public static final String LAYER_DISCARD = "discard";

    /** noul 给的是"是"的概率；过半数才提示重复（阈值只影响徽标，不自动执行） */
    public static final double DUPLICATE_THRESHOLD = 0.5;

    /**
     * 题面版本：<b>改题面文案、选项或等级数都要 +1</b>。
     *
     * <p>CAP-56 §3 定的口径：题面版本进评测指标主键。题面是代码常量，历史指标若不记版本，
     * 下一次改题面会让旧报告被无声地"对齐"到新题面上——指标看着没变，其实测的不是一回事。</p>
     */
    public static final String VERSION = "kb-proposal-triage@1";

    /**
     * state 里 {@code similar_entries} 的<b>空召回占位文案</b>。
     *
     * <p>CAP-56 FR-02 把「空召回」列为必须存在的对照组：没有它，「恒答重复」这种退化看起来
     * 也"合理"（2026-09-22 实测 multilingual 在空召回组仍给 duplicate=0.988）。对照组靠
     * <b>逐字这个串</b>来构造与识别，所以它必须是常量、由生产侧（CAP-57 解耦前为
     * {@code TriageEvidence}，现由未来消费方沿用）与评测侧共用——两边各写一份，改一处就静默失配。</p>
     */
    public static final String EMPTY_RECALL = "（未召回到相似条目）";

    /** 质量三级的题面文案（下标即分值，展示标签取冒号前那截） */
    public static final List<String> QUALITY_LEVELS = List.of(
            "含糊不可用：结论不明确，或缺少复现与判断所需的关键上下文",
            "可用需润色：方向对，但表述零散、步骤不全，整理后才好用",
            "直接可用：一句话能看懂、照着能做，无需补充背景");

    private static final Map<String, String> LAYER_LABELS = Map.of(
            LAYER_GLOBAL, "采纳到全局",
            LAYER_PROJECT, "采纳到项目",
            LAYER_DISCARD, "建议放弃");

    private TriageQuestions() {
    }

    /**
     * 三题定义（顺序即题面顺序，便于日志与 diff 对照）。
     * instructions 里回引 state 的键名，与 laya 官方 preset 的写法一致。
     */
    public static Map<String, Map<String, Object>> standard() {
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put(Q_LAYER, layerQuestion());
        questions.put(Q_DUPLICATE, duplicateQuestion());
        questions.put(Q_QUALITY, qualityQuestion());
        return questions;
    }

    private static Map<String, Object> layerQuestion() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put(LAYER_GLOBAL, "全平台通用：任何项目、任何库都成立的做法或约定");
        criteria.put(LAYER_PROJECT, "只对本项目成立：与该项目特有的环境、流程、历史强相关");
        criteria.put(LAYER_DISCARD, "不值得沉淀：一次性问题、常识、或表述不足以被别人复用");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "choice");
        q.put("instructions", "根据 `proposal_title` 与 `proposal_content`，这条经验应当采纳到哪一层？");
        q.put("criteria", criteria);
        return q;
    }

    private static Map<String, Object> duplicateQuestion() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "noul");
        q.put("instructions", "`proposal_content` 是否与 `similar_entries` 里已收录的条目实质重复"
                + "（同一件事换了个说法，采纳后会留下两条近义经验）？");
        return q;
    }

    private static Map<String, Object> qualityQuestion() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "score");
        q.put("instructions", "`proposal_content` 作为一条可复用经验的质量如何？");
        q.put("criteria", QUALITY_LEVELS);
        return q;
    }

    /** 采纳层级的中文短标签（未知取值原样回显——模型偶尔会造 key，别把徽标弄成空白） */
    public static String layerLabel(String value) {
        if (value == null) {
            return "";
        }
        return LAYER_LABELS.getOrDefault(value, value);
    }

    /** 质量等级的中文短标签（取题面文案冒号前那截；越界返回空串） */
    public static String qualityLabel(Integer level) {
        if (level == null || level < 0 || level >= QUALITY_LEVELS.size()) {
            return "";
        }
        return shortLabel(QUALITY_LEVELS.get(level));
    }

    /** "标签：说明" → "标签"（全角/半角冒号都认；没有冒号就整条当标签） */
    public static String shortLabel(String text) {
        if (text == null) {
            return "";
        }
        int cut = text.length();
        int full = text.indexOf('：');
        int half = text.indexOf(':');
        if (full >= 0) {
            cut = Math.min(cut, full);
        }
        if (half >= 0) {
            cut = Math.min(cut, half);
        }
        return text.substring(0, cut).trim();
    }
}
