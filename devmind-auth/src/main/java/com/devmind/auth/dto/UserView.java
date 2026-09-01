package com.devmind.auth.dto;

import java.time.Instant;

public record UserView(String id, String username, String displayName, String role, String status,
                       Instant createdAt) {
}
