package com.devmind.worklog.dto;

import java.util.List;

/**
 * CAP-93 FR-02 AI 生成 OKR 的解析树节点（O 嵌套 KR）。草稿 items_json 序列化、
 * 草稿视图与采纳请求三方共用同一形状（采纳时前端回传编辑后的树）。
 *
 * @param title            标题（必填，≤256）
 * @param content          详情（可空）
 * @param weight           权重%（可空，0~100）
 * @param parentYearGoalId 季度 O 可选挂的年度 O id（仅 QUARTER scope 有意义；YEAR O 必空）
 * @param krs              KR 列表（可空=无 KR）
 */
public record SuggestedObjective(String title, String content, Integer weight,
                                 Long parentYearGoalId, List<SuggestedKr> krs) {

    /** AI 建议的 KR 行。 */
    public record SuggestedKr(String title, String content, Integer weight) {
    }
}
