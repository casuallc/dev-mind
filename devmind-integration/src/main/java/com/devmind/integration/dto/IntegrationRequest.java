package com.devmind.integration.dto;

/**
 * Integration 创建/更新请求。token 创建时必填；更新时空白表示保持不变。
 * authType：PAT（默认，token=PAT）/ BASIC（Jira 8.13 及更早，username=用户名、token=密码）；
 * BASIC 更新时 username 留空 = 沿用原用户名。
 */
public record IntegrationRequest(String type, String name, String baseUrl,
                                 String authType, String username,
                                 String token, String configJson) {}
