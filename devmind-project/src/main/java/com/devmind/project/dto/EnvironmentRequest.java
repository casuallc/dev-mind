package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record EnvironmentRequest(
        @NotBlank String name,
        String description,
        List<Long> serverIds,
        Map<String, String> variables,
        List<String> secrets) {
}
