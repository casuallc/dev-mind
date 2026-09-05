package com.devmind.integration.dto;

/** CAP-27：工时登记结果（本次登记秒数 + 刷新后的远端状态；前端随后整体刷新需求视图） */
public record JiraWorklogResultView(long seconds, String remoteStatus) {
}
