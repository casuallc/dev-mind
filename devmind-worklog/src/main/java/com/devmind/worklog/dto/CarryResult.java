package com.devmind.worklog.dto;

import java.util.List;

/** 滚转结果（CAP-88 FR-05）：carried=成功滚转数；skipped=跳过数（已 CARRIED/已完结项，幂等）；items=新建副本。 */
public record CarryResult(int carried, int skipped, List<PlanItemView> items) {}
