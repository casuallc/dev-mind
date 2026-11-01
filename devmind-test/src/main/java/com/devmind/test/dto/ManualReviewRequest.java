package com.devmind.test.dto;

/** CAP-104 人工复核/人工判定：CONFIRMED / REJECTED / PASS / FAIL。 */
public record ManualReviewRequest(String review, String note) {
}
