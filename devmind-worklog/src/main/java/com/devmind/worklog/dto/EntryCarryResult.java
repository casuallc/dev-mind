package com.devmind.worklog.dto;

import java.util.List;

/** 条目滚转结果（CAP-90 FR-05）：carried=成功滚转数；skipped=跳过数（已 CARRIED/已完结，幂等）；items=新建副本。 */
public record EntryCarryResult(int carried, int skipped, List<EntryView> items) {}
