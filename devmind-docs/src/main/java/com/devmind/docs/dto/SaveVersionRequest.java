package com.devmind.docs.dto;

/**
 * 保存新版本请求（FR-02/FR-04）。frozen 状态下 changeNote 必填。
 */
public record SaveVersionRequest(String contentMd, String changeNote) {
}
