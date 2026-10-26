package com.devmind.worklog.dto;

import java.util.List;

/**
 * 目标滚转结果（CAP-91 FR-04）：carried=成功滚转数；skipped=幂等跳过数（非 ACTIVE）；
 * items=新建副本。
 */
public record GoalCarryResult(int carried, int skipped, List<GoalView> items) {}
