package com.devmind.integration.dto;

/**
 * Integration 创建/更新请求。token 创建时必填；更新时空白表示保持不变。
 */
public record IntegrationRequest(String type, String name, String baseUrl,
                                 String token, String configJson) {}
