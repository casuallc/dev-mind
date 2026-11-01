package com.devmind.test.dto;

/** CAP-104 资产评审：APPROVED / REJECTED；未通过资产不可被方案引用或执行。 */
public record TestAssetReviewRequest(String reviewStatus, String note) {
}
