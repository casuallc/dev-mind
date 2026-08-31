package com.devmind.project.dto;

public record BuildStepView(
        Long id,
        String projectId,
        int sortOrder,
        String name,
        String command,
        String workingDir,
        String location) {
}
