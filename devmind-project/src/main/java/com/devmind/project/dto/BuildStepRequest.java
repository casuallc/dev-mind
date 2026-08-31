package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

public record BuildStepRequest(
        int sortOrder,
        String name,
        @NotBlank String command,
        String workingDir,
        String location) {
}
