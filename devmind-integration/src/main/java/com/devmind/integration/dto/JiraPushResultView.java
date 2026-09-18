package com.devmind.integration.dto;

/**
 * CAP-47 推送/刷新结果：externalKey + 可点击地址 + 回读到的远端状态与任务类型 + 是否被同步覆盖。
 * 回读失败时 {@code remoteStatus}/{@code issueType} 为 null（link 已登记，前端提示走「从 Jira 刷新」）。
 */
public record JiraPushResultView(String externalKey,
                                 String externalUrl,
                                 String remoteStatus,
                                 String issueType,
                                 boolean syncCovered) {
}
