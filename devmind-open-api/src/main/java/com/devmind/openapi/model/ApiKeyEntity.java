package com.devmind.openapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * api_keys 表（CAP-20）：open-api 的 AK/SK 凭证。
 * secret 不可逆——只存 SHA-256 哈希；签名时双方以 sha256(sk) 作为 HMAC 密钥，sk 本身不上网也不落库。
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Access Key（唯一索引，请求头 X-Access-Key 携带），形如 ak_+24 位 hex */
    @Column(name = "access_key", nullable = false, unique = true, length = 32)
    private String accessKey;

    /** sha256hex(secret)，HMAC 签名实际使用的密钥材料 */
    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private Boolean enabled;

    /** 可空 = 永不过期（一次性密钥会设置较短过期时间） */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
