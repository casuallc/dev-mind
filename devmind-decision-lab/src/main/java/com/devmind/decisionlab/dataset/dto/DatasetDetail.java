package com.devmind.decisionlab.dataset.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-56 评测集详情：列表行 + "现在能不能冻结"的现场证据。
 *
 * <p>{@code caseGroupCounts} / {@code coverage} / {@code warnings} 都是<b>现算</b>的（不落库）：
 * 草稿期它们随标注进度变，缓存只会带来"显示说齐了、冻结说过不了"的自相矛盾。
 * 冻结后 {@code warnings} 恒空——它已经通过了当时的校验。</p>
 *
 * @param caseGroupCounts 各组条数（键是机器值，缺的组为 0 而不是不出现——界面要能一眼看出缺哪个）
 * @param coverage        每题已被标注的条数（题面里没有的题不出现在这里）
 * @param warnings        冻结前会挡住的理由（缺对照组等）与提醒（样本量偏少等）
 * @param manifest        冻结旁证（条数/题面版本/组分布/冻结时间）；草稿为空 map
 */
public record DatasetDetail(
        DatasetView dataset,
        Map<String, Long> caseGroupCounts,
        List<QuestionCoverage> coverage,
        List<String> warnings,
        Map<String, Object> manifest) {

    /** 某题已被标注的条数（分母取集内总条数，界面自己算比例） */
    public record QuestionCoverage(String questionId, String type, long annotated) {
    }
}
