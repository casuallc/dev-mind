package com.devmind.worklog.dto;

import java.util.List;

/**
 * 整年目标树（CAP-91 FR-02）：年度 O 列表（各嵌套年度 KR）+ Q1~Q4 四季度区
 * （无目标的季度也返回空区，前端固定四区渲染）。
 */
public record GoalTreeView(List<GoalNode> yearObjectives, List<QuarterSection> quarters) {

    /** O + 其下 KR 列表。 */
    public record GoalNode(GoalView goal, List<GoalView> krs) {}

    /** 一个季度区：period='2026-Q1'，objectives 为该季 O（各嵌套 KR）。 */
    public record QuarterSection(String period, List<GoalNode> objectives) {}
}
