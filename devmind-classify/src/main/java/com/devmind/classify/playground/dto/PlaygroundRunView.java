package com.devmind.classify.playground.dto;

import com.devmind.common.decision.DecisionAnswer;
import java.util.Map;

/**
 * CAP-57 FR-04 试分类结果：逐题答案 + 路由（实际用了哪个 checkpoint、为什么）+ recordRefId
 * （decision_records 血缘，前端可跳到决策记录页查证）。
 */
public record PlaygroundRunView(String recordRefId, String target,
                                Map<String, DecisionAnswer> answers,
                                String routingModel, String routingReason,
                                boolean degraded, String degradedReason, long latencyMs) {
}
