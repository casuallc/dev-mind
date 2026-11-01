package com.devmind.test.dto;

/** CAP-104 套件评审：仅 DRAFT 可提交，APPROVED 后才允许作为回归资产执行（来源资产套件）。 */
public record SuiteReviewRequest(String reviewStatus, String note) {
}
