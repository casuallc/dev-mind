package com.devmind.openapi.service;

import com.devmind.auth.IdentityService;
import com.devmind.openapi.dto.ApiKeyView;
import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.repo.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * API Key 管理（CAP-20）：签发/列表/启停/删除。
 * secret 形如 sk_+48 位 hex，明文仅签发时返回一次；库里只存 sha256hex(secret)，
 * HMAC 认证（OpenApiAuthFilter）以该哈希作为签名密钥。
 */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repo;
    private final IdentityService identity;

    public ApiKeyService(ApiKeyRepository repo, IdentityService identity) {
        this.repo = repo;
        this.identity = identity;
    }

    /**
     * 签发密钥。
     *
     * @return [secret 明文（仅此一次可见）, 实体]
     */
    @Transactional
    public Object[] issue(String name, Instant expiresAt) {
        String accessKey = "ak_" + randomHex(12);
        String secret = "sk_" + randomHex(24);
        ApiKeyEntity k = new ApiKeyEntity();
        k.setAccessKey(accessKey);
        k.setSecretHash(sha256Hex(secret));
        k.setName(name.trim());
        k.setEnabled(true);
        k.setExpiresAt(expiresAt);
        k.setCreatedBy(identity.currentActor());
        k.setCreatedAt(Instant.now());
        return new Object[]{secret, repo.save(k)};
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> list() {
        return repo.findAll().stream().map(ApiKeyService::toView).toList();
    }

    @Transactional
    public ApiKeyView setEnabled(Long id, boolean enabled) {
        ApiKeyEntity k = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API Key 不存在: " + id));
        k.setEnabled(enabled);
        return toView(repo.save(k));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("API Key 不存在: " + id);
        }
        repo.deleteById(id);
    }

    /**
     * HMAC 认证入口：按 accessKey 查可用（存在 + 启用 + 未过期）的密钥。
     * 签名比对由调用方用 {@link ApiKeyEntity#getSecretHash()} 作为 HMAC 密钥完成；
     * 比对通过后调用方应调 {@link #touchLastUsed} 刷新最近使用时间。
     */
    @Transactional(readOnly = true)
    public Optional<ApiKeyEntity> findVerifiable(String accessKey) {
        if (accessKey == null || accessKey.isBlank()) {
            return Optional.empty();
        }
        return repo.findByAccessKey(accessKey)
                .filter(k -> Boolean.TRUE.equals(k.getEnabled()))
                .filter(k -> k.getExpiresAt() == null || k.getExpiresAt().isAfter(Instant.now()));
    }

    @Transactional
    public void touchLastUsed(Long id) {
        repo.findById(id).ifPresent(k -> {
            k.setLastUsedAt(Instant.now());
            repo.save(k);
        });
    }

    public static ApiKeyView toView(ApiKeyEntity k) {
        return new ApiKeyView(k.getId(), k.getAccessKey(), k.getName(), k.getEnabled(),
                k.getExpiresAt(), k.getLastUsedAt(), k.getCreatedBy(), k.getCreatedAt());
    }

    /** sha256hex：secret 落库哈希，同时是 HMAC 签名密钥（签名方本地对 sk 做同样计算） */
    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
