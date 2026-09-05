package com.devmind.integration.dto;

/** CAP-19 FR-08：转换执行结果（已执行的转换 + 刷新后的远端状态） */
public record JiraTransitionResultView(JiraTransitionView transition, String remoteStatus) {
}
