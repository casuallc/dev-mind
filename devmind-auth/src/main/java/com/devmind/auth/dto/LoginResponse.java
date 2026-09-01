package com.devmind.auth.dto;

public record LoginResponse(String accessToken, String refreshToken, UserView user) {
}
