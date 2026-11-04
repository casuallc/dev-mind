package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * security_project_configs 表（CAP-66 FR-10-1）：项目级扫描配置，一行一项目。
 * 内部构件白名单的项目追加部分（平台默认走 devmind.security.internal-group-prefixes）。
 */
@Entity
@Table(name = "security_project_configs")
public class SecurityProjectConfigEntity {

    @Id
    @Column(name = "project_id", length = 64)
    private String projectId;

    /** 项目追加的内部 groupId 前缀（逗号分隔），与平台默认列表合并生效 */
    @Column(name = "internal_group_prefixes", length = 1024)
    private String internalGroupPrefixes;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getInternalGroupPrefixes() { return internalGroupPrefixes; }
    public void setInternalGroupPrefixes(String internalGroupPrefixes) { this.internalGroupPrefixes = internalGroupPrefixes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
