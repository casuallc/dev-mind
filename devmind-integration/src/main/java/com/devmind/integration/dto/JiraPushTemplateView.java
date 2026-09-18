package com.devmind.integration.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-10：个人 Jira 推送模板视图（列表/编辑回显用）。
 *
 * <p>{@code integrationName} 仅展示用（列表显示「公司 Jira」而不是 id 7）；
 * {@code extraFields} 与 {@link JiraPushTemplateRequest#extraFields()} 同形态。
 */
public record JiraPushTemplateView(Long id,
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
