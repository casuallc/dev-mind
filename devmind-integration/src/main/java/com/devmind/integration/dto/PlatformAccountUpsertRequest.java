package com.devmind.integration.dto;

/**
 * CAP-35 FR-01 我的平台账号 upsert 请求。
 * secret 更新时留空 = 不修改（沿用 Integration 编辑语义）；
 * username 仅 Jira BASIC 必填；gitAuthorName/Email 仅 git 平台（GITLAB/GITHUB）必填。
 */
public record PlatformAccountUpsertRequest(String authType, String username, String secret,
                                           String gitAuthorName, String gitAuthorEmail) {
}
