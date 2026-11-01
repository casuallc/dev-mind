package com.devmind.test.dto;

/** CAP-104 用例评审：APPROVED 通过 / DEPRECATED 废弃；note 写入版本记录。 */
public record CaseReviewRequest(String reviewStatus, String note) {
}
