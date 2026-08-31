package com.devmind.auth;

import com.devmind.auth.model.ApiTokenEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.ApiTokenRepository;
import com.devmind.auth.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 身份服务（P0-2 Identity 最小版）：给所有模块一个稳定的「当前操作者」。
 * 本地单用户阶段 {@link #currentActor()} 恒为种子用户 local；接入真实登录（CAP-01）后
 * 只需改本类实现，各业务表的 created_by 写入点不变。
 */
@Service
public class IdentityService {

    public static final String LOCAL_USER = "local";

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final ApiTokenRepository tokenRepo;

    public IdentityService(UserRepository userRepo, ApiTokenRepository tokenRepo) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
    }

    @PostConstruct
    void seed() {
        if (userRepo.findByUsername(LOCAL_USER).isEmpty()) {
            UserEntity u = new UserEntity();
            u.setId(shortId());
            u.setUsername(LOCAL_USER);
            u.setDisplayName("本机用户");
            u.setRole("OWNER");
            u.setCreatedAt(Instant.now());
            userRepo.save(u);
            log.info("种子用户已创建: local（本地单用户模式）");
        }
    }

    /** 当前操作者标识（写各表 created_by 用）。登录接入前恒为 local。 */
    public String currentActor() {
        return LOCAL_USER;
    }

    public Optional<UserEntity> currentUser() {
        return userRepo.findByUsername(LOCAL_USER);
    }

    /**
     * 签发 API 令牌：返回明文令牌（仅此一次可见），库里只存 SHA-256 哈希。
     *
     * @return [明文令牌, 实体]
     */
    public Object[] issueToken(String name, Instant expiresAt) {
        UserEntity user = currentUser()
                .orElseThrow(() -> new IllegalStateException("种子用户不存在"));
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = "dmt_" + HexFormat.of().formatHex(raw);
        ApiTokenEntity t = new ApiTokenEntity();
        t.setUserId(user.getId());
        t.setName(name == null || name.isBlank() ? "api-token" : name.trim());
        t.setTokenHash(sha256(token));
        t.setEnabled(true);
        t.setExpiresAt(expiresAt);
        t.setCreatedAt(Instant.now());
        return new Object[]{token, tokenRepo.save(t)};
    }

    /** 校验令牌：存在 + 启用 + 未过期；命中时刷新 lastUsedAt。 */
    public Optional<ApiTokenEntity> verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiTokenEntity> found = tokenRepo.findByTokenHash(sha256(token))
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(Instant.now()));
        found.ifPresent(t -> {
            t.setLastUsedAt(Instant.now());
            tokenRepo.save(t);
        });
        return found;
    }

    private String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String shortId() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
