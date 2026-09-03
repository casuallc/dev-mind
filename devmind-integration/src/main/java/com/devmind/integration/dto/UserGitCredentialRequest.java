package com.devmind.integration.dto;

/**
 * CAP-24 FR-01 我的 Git 凭证写请求。secret 更新时留空 = 不修改（沿用 Integration 编辑语义）。
 */
public record UserGitCredentialRequest(String label, String baseUrl, String secret,
                                       String gitAuthorName, String gitAuthorEmail) {
}
