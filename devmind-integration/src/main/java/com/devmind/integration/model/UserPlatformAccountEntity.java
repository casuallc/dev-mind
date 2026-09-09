package com.devmind.integration.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * user_platform_accounts 表（CAP-35 FR-01）：用户在某个平台实例（Integration）上的个人账号。
 * (user_id, integration_id) 唯一（应用层判重）；secret 以 enc1: AES-GCM 密文落库
 * （BASIC 密文内容沿用 "username\npassword" 格式），任何视图不回显明文。
 * git 平台（GITLAB/GITHUB）附带提交署名；Jira 不需要署名。
 */
@Entity
@Table(name = "user_platform_accounts")
public class UserPlatformAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.id，弱关联不设外键） */
    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    /** 绑定的平台实例（integrations.id） */
    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    /** PAT / BASIC（不得与实例类型冲突：git 平台仅 PAT；Jira 两者皆可） */
    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = IntegrationEntity.AUTH_PAT;

    /** 仅 Jira BASIC：登录用户名（非敏感，视图可回显） */
    @Column(length = 128)
    private String username;

    /** enc1: 密文（AES-GCM；PAT=原样 token，BASIC="username\npassword"），永不明文回显 */
    @Column(name = "secret_enc", length = 2048)
    private String secretEnc;

    /** 提交署名 name（git 平台必填；Jira 恒空） */
    @Column(name = "git_author_name", length = 128)
    private String gitAuthorName;

    /** 提交署名 email（git 平台必填；Jira 恒空） */
    @Column(name = "git_author_email", length = 256)
    private String gitAuthorEmail;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
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
