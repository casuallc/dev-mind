package com.devmind.classify.playground.dto;

import java.util.Map;

/**
 * CAP-57 FR-04 试分类请求。三通道互斥（service 层校验）：
 * instanceId（直打受管实例）/ endpointId（指定 DECISION 端点）/ 都空（平台默认决策链）。
 */
public record PlaygroundRunRequest(Long instanceId, Long endpointId,
                                   Map<String, Object> state,
                                   Map<String, Map<String, Object>> questions) {
}
