package com.devmind.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-03 推送入参。日期用 {@code String yyyy-MM-dd}（沿用 RequirementRequest.dueDate 口径）；
 * 无布尔字段——回链由服务端强制追加，不给开关。
 *
 * <p>{@code backlinkUrl} 由前端按 {@code window.location.origin} 拼出平台需求详情页地址，
 * 服务端只负责拼回链文案格式（{@code 需求编号 · URL}）并追加到描述尾部。
 *
 * <p>{@code extraFields} 为 CAP-47 FR-08 的动态必填字段：键是平台字段 id，值是**已按该字段类型
 * 组装好的平台取值**（前端拿到 {@link JiraCreateFieldView#control()} 后按控件类型组装，
 * 见该控件常量）。服务端只做两道护栏——不许覆盖固定字段、值形态限两层以内——不重解释取值语义。
 */
public record JiraPushRequest(Long integrationId,
                              String jiraProjectKey,
                              String issueTypeId,
                              String summary,
                              String description,
                              String backlinkUrl,
                              String priorityName,
                              String assigneeName,
                              List<String> labels,
                              String dueDate,
                              Map<String, Object> extraFields) {
}
