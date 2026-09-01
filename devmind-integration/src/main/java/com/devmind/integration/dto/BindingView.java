package com.devmind.integration.dto;

import java.time.Instant;

/**
 * 项目绑定视图（含 Integration 与仓库的展示信息）。
 */
public record BindingView(Long id, Long integrationId, String integrationName, String integrationType,
                          String projectId, Long repoId, String repoName,
                          String externalProjectKey, String status, Instant createdAt) {}
