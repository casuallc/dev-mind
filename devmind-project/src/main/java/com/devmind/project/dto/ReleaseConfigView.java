package com.devmind.project.dto;

public record ReleaseConfigView(
        Long id,
        String projectId,
        String nexusRepo,
        String scriptTemplateRef,
        String versionRule,
        String executor,
        Long remoteServerId) {
}
