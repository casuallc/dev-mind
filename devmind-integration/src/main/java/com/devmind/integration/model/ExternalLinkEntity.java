package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * external_links 表（CAP-18 FR-07）：内部实体 ↔ 外部对象映射，追溯与幂等的依据。
 * 如 WI ↔ MR（external_key=iid）、Release ↔ GitLab Release（external_key=tag_name）。
 */
@Entity
@Table(name = "external_links")
public class ExternalLinkEntity {

    /** 内部对象：Work Item */
    public static final String INTERNAL_WORK_ITEM = "WORK_ITEM";
    /** 内部对象：发版单 */
    public static final String INTERNAL_RELEASE = "RELEASE";
    /** 内部对象：需求（Jira issue 同步落点） */
    public static final String INTERNAL_REQUIREMENT = "REQUIREMENT";

    /** 外部对象：Merge Request */
    public static final String EXTERNAL_MR = "MR";
    /** 外部对象：平台 Release（GitLab Release / GitHub Release） */
    public static final String EXTERNAL_TAG_RELEASE = "TAG_RELEASE";
    /** 外部对象：Issue（Jira 同步，后续） */
    public static final String EXTERNAL_ISSUE = "ISSUE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    /** WORK_ITEM / RELEASE / … */
    @Column(name = "internal_type", nullable = false, length = 24)
    private String internalType;

    @Column(name = "internal_id", nullable = false, length = 64)
    private String internalId;

    /** MR / TAG_RELEASE / ISSUE / … */
    @Column(name = "external_type", nullable = false, length = 24)
    private String externalType;

    /** 平台侧键：MR iid、tag_name、issue key 等 */
    @Column(name = "external_key", nullable = false, length = 256)
    private String externalKey;

    /** 平台侧页面地址（详情页跳转用） */
    @Column(name = "external_url", length = 1024)
    private String externalUrl;

    /** OPEN / MERGED / CLOSED / CREATED 等平台态（出站 MVP 仅创建时登记） */
    @Column(length = 24)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getInternalType() { return internalType; }
    public void setInternalType(String internalType) { this.internalType = internalType; }
    public String getInternalId() { return internalId; }
    public void setInternalId(String internalId) { this.internalId = internalId; }
    public String getExternalType() { return externalType; }
    public void setExternalType(String externalType) { this.externalType = externalType; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
