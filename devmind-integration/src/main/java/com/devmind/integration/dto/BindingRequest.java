package com.devmind.integration.dto;

/**
 * 项目绑定请求。repoId 缺省=项目主库；externalProjectKey 缺省时尝试从 remote_url 推断（GitLab path）。
 */
public record BindingRequest(Long integrationId, Long repoId, String externalProjectKey) {}
