package com.devmind.integration.dto;

import java.time.Instant;

/**
 * CAP-75 FR-14 管理端用户平台账号视图（跨用户）：一行 = 一条绑定记录，
 * 联查平台登录名/displayName 与实例信息；永不含 secret。
 * externalUsername = 第三方侧用户名（BASIC 登录名 / FEISHU open_id 身份码）。
 */
public record AdminPlatformAccountView(Long id, Long integrationId, String integrationType,
                                       String integrationName, String userId, String username,
                                       String displayName, String externalUsername, String authType,
                                       Instant createdAt, Instant updatedAt) {
}
