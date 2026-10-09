package com.devmind.agent.dto;

import java.time.Instant;

/** CAP-70：出口规则视图（附节点名与隧道/协议状态，省得前端二次聚合） */
public record EgressRuleView(
        Long id,
        String hostPattern,
        Long nodeId,
        String nodeName,
        boolean enabled,
        int sort,
        String remark,
        Integer nodeProtocolVersion,
        Boolean tunnelOnline,
        Instant createdAt,
        Instant updatedAt) {
}
