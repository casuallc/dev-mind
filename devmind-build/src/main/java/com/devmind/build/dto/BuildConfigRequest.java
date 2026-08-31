package com.devmind.build.dto;

/** 构建配置写入（CAP-08 FR-02） */
public record BuildConfigRequest(String executor, Long remoteServerId, Integer concurrencyLimit) {
}
