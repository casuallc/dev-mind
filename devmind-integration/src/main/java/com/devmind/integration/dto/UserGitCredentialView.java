package com.devmind.integration.dto;

import java.time.Instant;

/**
 * CAP-24 FR-01 我的 Git 凭证视图。永不含 secret 明文，仅 hasSecret 标识是否已配置
 * （对齐 IntegrationView.hasToken 约定）。
 */
public record UserGitCredentialView(Long id, String label, String baseUrl,
                                    String gitAuthorName, String gitAuthorEmail,
                                    boolean hasSecret, Instant createdAt, Instant updatedAt) {
}
