package com.devmind.auth;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RefreshTokenRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthService authService = mock(AuthService.class);
    private UserAdminService service;

    private UserEntity user(String id, String username, String role) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setStatus(UserEntity.STATUS_ACTIVE);
        u.setPasswordHash(encoder.encode("secret1"));
        u.setCreatedAt(Instant.now());
        return u;
    }

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepo, refreshRepo, encoder, authService);
    }

    @Test
    void 删除用户连带清理refreshToken() {
        UserEntity target = user("u2", "bob", UserEntity.ROLE_DEVELOPER);
        when(userRepo.findById("u2")).thenReturn(Optional.of(target));

        service.delete("u2", "admin");

        verify(refreshRepo).deleteByUserId("u2");
        verify(userRepo).delete(target);
    }

    @Test
    void 不能删除自己() {
        UserEntity me = user("u1", "admin", UserEntity.ROLE_ADMIN);
        when(userRepo.findById("u1")).thenReturn(Optional.of(me));

        assertThrows(DevMindException.class, () -> service.delete("u1", "admin"));
        verify(userRepo, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 不能删除系统身份local() {
        UserEntity local = user("u0", "local", UserEntity.ROLE_ADMIN);
        local.setPasswordHash(null);
        when(userRepo.findById("u0")).thenReturn(Optional.of(local));

        assertThrows(DevMindException.class, () -> service.delete("u0", "admin"));
        verify(userRepo, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 不能删除最后一个可登录ADMIN() {
        UserEntity target = user("u1", "root", UserEntity.ROLE_ADMIN);
        when(userRepo.findById("u1")).thenReturn(Optional.of(target));
        when(userRepo.findAll()).thenReturn(List.of(target));

        assertThrows(DevMindException.class, () -> service.delete("u1", "admin"));
        verify(userRepo, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 存在另一个ADMIN时可删除ADMIN() {
        UserEntity target = user("u1", "root", UserEntity.ROLE_ADMIN);
        UserEntity other = user("u9", "ops", UserEntity.ROLE_ADMIN);
        when(userRepo.findById("u1")).thenReturn(Optional.of(target));
        when(userRepo.findAll()).thenReturn(List.of(target, other));

        service.delete("u1", "ops");

        verify(userRepo).delete(target);
    }

    @Test
    void 无密码的ADMIN不占最后一个可登录ADMIN名额() {
        UserEntity target = user("u1", "root", UserEntity.ROLE_ADMIN);
        UserEntity other = user("u9", "nopass", UserEntity.ROLE_ADMIN);
        other.setPasswordHash(null);
        when(userRepo.findById("u1")).thenReturn(Optional.of(target));
        when(userRepo.findAll()).thenReturn(List.of(target, other));

        assertThrows(DevMindException.class, () -> service.delete("u1", "admin"));
        verify(userRepo, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(refreshRepo, never()).deleteByUserId(anyString());
    }
}
