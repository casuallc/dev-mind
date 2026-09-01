package com.devmind.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(@NotBlank String username, String displayName,
                                @NotBlank @Size(min = 6, max = 64) String password,
                                @NotBlank String role) {
}
