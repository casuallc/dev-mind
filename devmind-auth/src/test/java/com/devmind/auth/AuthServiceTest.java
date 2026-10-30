package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.dto.UserView;
import com.devmind.auth.model.RefreshTokenEntity;
import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RefreshTokenRepository;
import com.devmind.auth.repo.RoleRepository;
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
    private final RoleRepository roleRepo = mock(RoleRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private JwtCodec codec;
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
        codec = new JwtCodec(props, new tools.jackson.databind.ObjectMapper());
        codec.init();
        service = new AuthService(userRepo, refreshRepo, encoder, codec, props, roleRepo);
        when(refreshRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 登录成功返回双token() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        var resp = service.login("alice", "secret1");
        assertTrue(resp.accessToken().contains("."));
        assertTrue(resp.refreshToken().startsWith("dmr_"));
        assertEquals("alice", resp.user().username());
        // 旧数据未写 role_code 时退化为基础权限码
        assertEquals(UserEntity.ROLE_DEVELOPER, resp.user().roleCode());
        assertEquals("DEVELOPER", resp.user().roleName());
    }

    @Test
    void 登录签发的JWT带角色编码() {
        UserEntity u = activeUser();
        u.setRoleCode("QA_LEAD");
        RoleEntity role = new RoleEntity();
        role.setCode("QA_LEAD");
        role.setName("测试负责人");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(role));

        var resp = service.login("alice", "secret1");

        assertEquals("QA_LEAD", resp.user().roleCode());
        assertEquals("测试负责人", resp.user().roleName());
        assertEquals(UserEntity.ROLE_DEVELOPER, resp.user().role());
        JwtCodec.JwtClaims claims = codec.verify(resp.accessToken()).orElseThrow();
        assertEquals(UserEntity.ROLE_DEVELOPER, claims.role());
        assertEquals("QA_LEAD", claims.roleCode());
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

    @Test
    void 用户视图带出角色编码与名称() {
        UserEntity u = activeUser();
        u.setRoleCode("QA_LEAD");
        RoleEntity role = new RoleEntity();
        role.setCode("QA_LEAD");
        role.setName("测试负责人");
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(role));

        UserView view = service.toView(u);

        assertEquals(UserEntity.ROLE_DEVELOPER, view.role());
        assertEquals("QA_LEAD", view.roleCode());
        assertEquals("测试负责人", view.roleName());
    }
}
