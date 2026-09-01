package com.devmind.auth.dto;

/** 更新用户：null 字段不动。role/status 传空串忽略。 */
public record UpdateUserRequest(String displayName, String role, String status) {
}
