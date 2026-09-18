package com.devmind.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-10：项目级 Jira 推送默认值的保存入参。
 *
 * <p>{@code extraFields} 为动态字段默认值：键是 Jira 字段 id（{@code components}/
 * {@code customfield_10207}…），值是**已按该字段类型组装好的 Jira 取值**（与
 * {@link JiraPushRequest#extraFields()} 同形态，由前端按控件类型组装后存）。
 * 不存 control/options——那些随 Jira 配置变化，由 createmeta 实时拉取。
 */
public record JiraPushDefaultsRequest(Long integrationId,
                                      String jiraProjectKey,
                                      String issueTypeId,
                                      String priorityName,
                                      String assigneeName,
                                      List<String> labels,
                                      Map<String, Object> extraFields) {
}
