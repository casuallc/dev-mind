package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * user_git_credentials 表（CAP-24 FR-01）：用户级 Git 平台凭证与提交署名。
 * 每用户每平台 host 一条；PAT 以 enc1: AES-GCM 密文落库（复用 IntegrationCipher），
 * 任何视图不回显明文。gitAuthorName/Email 随凭证走（同一人在不同平台邮箱可能不同）。
 */
@Entity
@Table(name = "user_git_credentials")
public class UserGitCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.id，弱关联不设外键） */
    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    /** 显示名（如"公司 GitLab"） */
    @Column(nullable = false, length = 128)
    private String label;

    /** 平台地址，如 https://gitlab.example.com；host 用于匹配仓库 remoteUrl */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** enc1: PAT 密文（AES-GCM），永不明文回显 */
    @Column(name = "secret_enc", nullable = false, length = 2048)
    private String secretEnc;

    /** 提交署名 name */
    @Column(name = "git_author_name", nullable = false, length = 128)
    private String gitAuthorName;

    /** 提交署名 email */
    @Column(name = "git_author_email", nullable = false, length = 256)
    private String gitAuthorEmail;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getGitAuthorName() { return gitAuthorName; }
    public void setGitAuthorName(String gitAuthorName) { this.gitAuthorName = gitAuthorName; }
    public String getGitAuthorEmail() { return gitAuthorEmail; }
    public void setGitAuthorEmail(String gitAuthorEmail) { this.gitAuthorEmail = gitAuthorEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
