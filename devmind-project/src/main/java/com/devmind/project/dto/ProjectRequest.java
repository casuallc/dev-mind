package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ProjectRequest(
        @NotBlank String name,
        @NotBlank String path,
        String defaultBranch,
        List<String> tags,
        String description,
        String status,
        String apiDocSource,
        Boolean autoRegressionOnDeploy) {
}
