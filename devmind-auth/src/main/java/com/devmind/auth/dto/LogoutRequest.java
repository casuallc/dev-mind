package com.devmind.auth.dto;

/** 登出：作废旧 refresh token（access token 自然过期）。 */
public record LogoutRequest(String refreshToken) {
}
