package com.devmind.integration.dto;

import java.time.Instant;

/**
 * CAP-35 FR-01 我的平台账号视图：一行 = 一个 ENABLED 实例 + 我的绑定状态
 * （未绑定实例 bound=false 也列出，前端引导配置）。永不含 secret 明文；
 * username 仅 BASIC 账号回显（登录名非敏感）。
 */
public record PlatformAccountView(Long integrationId, String integrationType, String integrationName,
                                  String baseUrl, boolean bound,
                                  String authType, String username, boolean hasSecret,
                                  String gitAuthorName, String gitAuthorEmail, Instant updatedAt) {
}
