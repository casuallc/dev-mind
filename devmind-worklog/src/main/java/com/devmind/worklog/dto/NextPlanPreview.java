package com.devmind.worklog.dto;

/** CAP-90 FR-10：下周计划项的确定性 markdown 投影预览（不经 LLM；itemCount=0 时 markdown 为空串）。 */
public record NextPlanPreview(String markdown, int itemCount) {}
