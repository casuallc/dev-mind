package com.devmind.worklog.dto;

import java.time.Instant;

/** 季度复盘视图（CAP-91 FR-08）：exists=false 时内容为 null（同日报 200 null 口径）。 */
public record QuarterlyReportView(boolean exists, String quarter, String contentMd,
                                  String sessionId, Instant createdAt, Instant updatedAt) {}
