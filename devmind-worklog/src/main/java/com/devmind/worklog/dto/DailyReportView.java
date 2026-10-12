package com.devmind.worklog.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * CAP-74：日报视图改服务端仓口径——无 DB id/status（状态机已删），exists 标识文件是否存在
 * （GET 不存在的日期返回 null，列表接口天然只列存在的，exists 恒 true，留给前端判空用）。
 */
public record DailyReportView(boolean exists, LocalDate workDate, String contentMd,
                              String sessionId, Instant createdAt, Instant updatedAt) {
}
