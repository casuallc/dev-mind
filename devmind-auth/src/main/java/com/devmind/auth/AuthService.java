package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.dto.LoginResponse;
import com.devmind.auth.dto.UserView;
import com.devmind.auth.model.RefreshTokenEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RefreshTokenRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * CAP-01 登录/刷新/登出。refresh token 只存 SHA-256 哈希，刷新即轮换（旧的一次性作废）。
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtCodec jwtCodec;
    private final AuthProperties props;

    public AuthService(UserRepository userRepo, RefreshTokenRepository refreshRepo,
                       PasswordEncoder passwordEncoder, JwtCodec jwtCodec, AuthProperties props) {
        this.userRepo = userRepo;
        this.refreshRepo = refreshRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtCodec = jwtCodec;
        this.props = props;
    }

    @Transactional
    public LoginResponse login(String username, String password) {
        UserEntity u = userRepo.findByUsername(username)
                .filter(UserEntity::isActive)
                .filter(x -> x.getPasswordHash() != null && passwordEncoder.matches(password, x.getPasswordHash()))
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        return issueTokens(u);
    }

    /** 刷新：旧 refresh 校验（哈希命中+未撤销+未过期+用户可用）后作废并签发新对。 */
    @Transactional
    public LoginResponse refresh(String refreshToken) {
        RefreshTokenEntity old = refreshRepo.findByTokenHash(sha256(refreshToken))
                .filter(t -> !t.isRevoked())
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "refresh token 无效或已过期"));
        UserEntity u = userRepo.findById(old.getUserId())
                .filter(UserEntity::isActive)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "用户不存在或已禁用"));
        old.setRevoked(true);
        refreshRepo.save(old);
        return issueTokens(u);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshRepo.findByTokenHash(sha256(refreshToken)).ifPresent(t -> {
            t.setRevoked(true);
            refreshRepo.save(t);
        });
    }

    public UserView toView(UserEntity u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getStatus(),
                u.getCreatedAt());
    }

    private LoginResponse issueTokens(UserEntity u) {
        String access = jwtCodec.issue(u.getUsername(), u.getRole(),
                Instant.now().plus(props.getAccessTtl()));
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String refresh = "dmr_" + HexFormat.of().formatHex(raw);
        RefreshTokenEntity rt = new RefreshTokenEntity();
        rt.setUserId(u.getId());
        rt.setTokenHash(sha256(refresh));
        rt.setExpiresAt(Instant.now().plus(props.getRefreshTtl()));
        rt.setRevoked(false);
        rt.setCreatedAt(Instant.now());
        refreshRepo.save(rt);
        return new LoginResponse(access, refresh, toView(u));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
