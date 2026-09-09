package com.devmind.project.dto;

public record ReleaseConfigRequest(
        String nexusRepo,
        String scriptTemplateRef,
        String versionRule,
        String executor,
        /** executor=AGENT 时的目标 runner 节点 id（CAP-36；空走节点路由链） */
        String agentNodeId) {
}
