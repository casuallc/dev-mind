package com.devmind.build.dto;

/** 构建配置写入（CAP-08 FR-02；CAP-36：remoteServerId 改为 agentNodeId；CAP-73：outputDir 部署物树目录） */
public record BuildConfigRequest(String executor, String agentNodeId, Integer concurrencyLimit,
                                 String outputDir) {
}
