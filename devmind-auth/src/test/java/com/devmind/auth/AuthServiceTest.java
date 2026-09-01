package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.model.RefreshTokenEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RefreshTokenRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private AuthService service;

    private UserEntity activeUser() {
        UserEntity u = new UserEntity();
        u.setId("u1");
        u.setUsername("alice");
        u.setRole(UserEntity.ROLE_DEVELOPER);
        u.setStatus(UserEntity.STATUS_ACTIVE);
        u.setPasswordHash(encoder.encode("secret1"));
        u.setCreatedAt(Instant.now());
        return u;
    }

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecret("auth-service-test-secret");
        JwtCodec codec = new JwtCodec(props, new tools.jackson.databind.ObjectMapper());
        codec.init();
        service = new AuthService(userRepo, refreshRepo, encoder, codec, props);
        when(refreshRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 登录成功返回双token() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        var resp = service.login("alice", "secret1");
        assertTrue(resp.accessToken().contains("."));
        assertTrue(resp.refreshToken().startsWith("dmr_"));
        assertEquals("alice", resp.user().username());
    }

    @Test
    void 密码错误或用户禁用返回401() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        assertThrows(DevMindException.class, () -> service.login("alice", "wrong"));

        UserEntity disabled = activeUser();
        disabled.setStatus(UserEntity.STATUS_DISABLED);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(disabled));
        assertThrows(DevMindException.class, () -> service.login("alice", "secret1"));

        when(userRepo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(DevMindException.class, () -> service.login("ghost", "x"));
    }

    @Test
    void 刷新轮换后旧token复用被拒() {
        UserEntity u = activeUser();
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(userRepo.findById("u1")).thenReturn(Optional.of(u));

        var first = service.login("alice", "secret1");
        // 模拟库：按哈希找到刚签发的 refresh
        RefreshTokenEntity stored = new RefreshTokenEntity();
        stored.setUserId("u1");
        stored.setTokenHash(AuthService.sha256(first.refreshToken()));
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        when(refreshRepo.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));

        var second = service.refresh(first.refreshToken());
        assertNotEquals(first.accessToken(), second.accessToken());
        assertTrue(stored.isRevoked(), "旧 refresh 应被作废");

        // 复用旧 refresh → 401
        assertThrows(DevMindException.class, () -> service.refresh(first.refreshToken()));
    }
}
