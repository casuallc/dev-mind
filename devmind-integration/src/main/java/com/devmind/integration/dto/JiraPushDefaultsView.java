package com.devmind.integration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-10：项目级 Jira 推送默认值视图。无配置时接口返回 null（前端渲染成空表单）。
 *
 * <p>{@code integrationName} 仅展示用（配置页显示「公司 Jira」而不是 id 7）；
 * {@code extraFields} 与 {@link JiraPushDefaultsRequest#extraFields()} 同形态。
 */
public record JiraPushDefaultsView(Long id,
                                   Long integrationId,
                                   String integrationName,
                                   String jiraProjectKey,
                                   String issueTypeId,
                                   String priorityName,
                                   String assigneeName,
                                   List<String> labels,
                                   Map<String, Object> extraFields,
                                   Instant createdAt,
                                   Instant updatedAt) {
}
