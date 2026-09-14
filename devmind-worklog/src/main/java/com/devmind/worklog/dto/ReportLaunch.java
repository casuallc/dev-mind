package com.devmind.worklog.dto;

/**
 * CAP-41 FR-03 报告生成受理回执。
 *
 * @param sessionId 生成会话 id（null = 未起会话：reused=true 已有报告，或无素材跳过）
 * @param reused    true = 已有报告且非 force，直接复用未重新生成
 */
public record ReportLaunch(String sessionId, boolean reused) {}
