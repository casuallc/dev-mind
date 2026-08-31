package com.devmind.build.dto;

/** 触发构建（FR-03/07）：commit/branch 不传则本地执行时取当前 HEAD；executor/remoteServerId 可临时覆盖配置。 */
public record TriggerRequest(String commit, String branch, String executor, Long remoteServerId, String requirementId) {
}
