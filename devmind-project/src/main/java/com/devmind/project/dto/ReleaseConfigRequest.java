package com.devmind.project.dto;

public record ReleaseConfigRequest(
        String nexusRepo,
        String scriptTemplateRef,
        String versionRule) {
}
