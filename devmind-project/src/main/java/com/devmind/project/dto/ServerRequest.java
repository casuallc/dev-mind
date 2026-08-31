package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ServerRequest(
        @NotBlank String name,
        String env,
        @NotBlank String accessType,
        String accessConfig,
        List<String> capabilities,
        Boolean enabled) {
}
