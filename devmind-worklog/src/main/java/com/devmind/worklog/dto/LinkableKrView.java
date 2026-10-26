package com.devmind.worklog.dto;

/** 计划项抽屉可关联 KR（CAP-91 FR-06）：当季 ACTIVE KEY_RESULT + 所属 O 标题。 */
public record LinkableKrView(Long id, String title, String objectiveTitle, String period) {}
