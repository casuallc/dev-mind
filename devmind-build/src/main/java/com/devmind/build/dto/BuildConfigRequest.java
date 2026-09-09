package com.devmind.build.dto;

/** 构建配置写入（CAP-08 FR-02；CAP-36：remoteServerId 改为 agentNodeId） */
public record BuildConfigRequest(String executor, String agentNodeId, Integer concurrencyLimit) {
}
