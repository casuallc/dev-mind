package com.devmind.worklog.dto;

/**
 * 季度复盘「从数据重建」预览（CAP-91 FR-10）：OKR 对照块 + 计划外聚合的确定性 markdown
 * （不经 LLM）；前端确认后填入编辑框，人工保存才落库（同 CAP-90 FR-10 哲学）。
 */
public record ReviewPreview(String markdown) {}
