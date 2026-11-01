package com.devmind.test.dto;

import java.util.List;
import java.util.Map;

/**
 * 方案运行触发参数。mode=SEQUENTIAL 按方案项/并行组编排；SHARD 将全部用例按 hash 分散到
 * nodeIds；MATRIX 在 nodeIds 上各跑全量。environmentId/baseUrl/parameters 覆盖方案默认值。
 */
public record CreatePlanRunRequest(
        Long environmentId,
        String baseUrl,
        String agentNodeId,
        List<String> nodeIds,
        String mode,
        Map<String, String> parameters,
        String workItemId,
        String requirementId,
        /** 仅按指定分片/节点重跑（可选）；空 = 全量 */
        List<String> shardKeys,
        /** 重跑时只重跑失败项（SEQUENTIAL）/失败分片 */
        Boolean failedOnly,
        /** FR-18 运行前覆盖：失败策略（缺省取方案） */
        String failStrategy,
        /** FR-18 运行前覆盖：并行度（缺省取方案） */
        Integer concurrency,
        /** FR-18 运行前覆盖：用例默认超时秒（方案项/用例自身超时优先） */
        Integer timeoutSec,
        /** FR-19 节点选择：标签要求（SHARD/MATRIX 未显式给 nodeIds 时匹配） */
        List<String> labels,
        /** FR-19 节点选择：能力要求（标签无命中时匹配） */
        List<String> capabilities) {
}
