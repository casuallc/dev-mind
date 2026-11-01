package com.devmind.test.dto;

/** 申请一次性证据上传 token：kind 限定 SCREENSHOT/LOG/ATTACHMENT/ARTIFACT/STDOUT/STDERR。 */
public record EvidenceTokenRequest(Long resultId, String nodeId, String kind) {
}
