package com.devmind.worklog.dto;

/** 季度复盘编辑保存请求（CAP-91 FR-08：按 period 寻址，保存即生效）。 */
public record UpdateQuarterlyRequest(String contentMd) {}
