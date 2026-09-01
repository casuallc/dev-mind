package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * integrations 表（CAP-18 FR-01）：一个外部平台实例的配置。
 * token 以 enc1: AES-GCM 密文落库（secret_enc），任何视图不回显明文。
 */
@Entity
@Table(name = "integrations")
public class IntegrationEntity {

    /** GitLab（MVP） */
    public static final String TYPE_GITLAB = "GITLAB";
    /** GitHub（后续） */
    public static final String TYPE_GITHUB = "GITHUB";
    /** Jira（后续，无 git 能力） */
    public static final String TYPE_JIRA = "JIRA";

    /** 个人访问令牌（MVP 唯一认证方式） */
    public static final String AUTH_PAT = "PAT";

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GITLAB / GITHUB / JIRA */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 128)
    private String name;

    /** 平台实例地址，如 https://gitlab.example.com（自托管仅 http/https） */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** PAT（MVP） */
    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = AUTH_PAT;

    /** enc1: 密文（AES-GCM），永不明文回显 */
    @Column(name = "secret_enc", length = 2048)
    private String secretEnc;

    /** ENABLED / DISABLED */
    @Column(length = 16)
    private String status = STATUS_ENABLED;

    /** 连接器专属配置 JSON（预留） */
    @Lob
    @Column(name = "config_json")
    private String configJson;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
