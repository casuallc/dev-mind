package com.devmind.test.dto;

import java.util.List;
import java.util.Map;

/**
 * 方案编排线程创建子运行的最小参数集。子运行同步执行（调用线程内跑完），
 * 并发由方案编排器控制；用例来自方案快照，不再读取实时用例。
 */
public record PlanChildRunSpec(
        String projectId,
        Long planRunId,
        Long planItemId,
        Integer attempt,
        String requirementId,
        String workItemId,
        Long environmentId,
        String agentNodeId,
        String baseUrl,
        String shardKey,
        List<Long> suiteIds,
        Long caseId,
        String runScope,
        Map<String, String> parameters,
        List<PlanSnapshot.TestCaseSnapshot> cases) {
}
