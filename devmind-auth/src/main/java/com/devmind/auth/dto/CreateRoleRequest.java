package com.devmind.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(@NotBlank @Size(max = 32) String code,
                                @NotBlank @Size(max = 64) String name,
                                @Size(max = 512) String description,
                                @NotBlank String baseRole) {
}
