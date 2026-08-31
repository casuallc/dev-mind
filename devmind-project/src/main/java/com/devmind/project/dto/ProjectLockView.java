package com.devmind.project.dto;

public record ProjectLockView(
        String projectId,
        int activeWrites,
        int maxConcurrent) {
}
