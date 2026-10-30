package com.devmind.auth;

import com.devmind.auth.dto.CreateUserRequest;
import com.devmind.auth.dto.UpdateUserRequest;
import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RefreshTokenRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    private final RoleService roleService = mock(RoleService.class);
    private UserAdminService service;

    private UserEntity user(String id, String username, String role) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setRoleCode(role);
        u.setStatus(UserEntity.STATUS_ACTIVE);
        u.setPasswordHash(encoder.encode("secret1"));
        u.setCreatedAt(Instant.now());
        return u;
    }

    private RoleEntity role(String code, String baseRole) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setName(code);
        role.setBaseRole(baseRole);
        return role;
    }

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepo, refreshRepo, encoder, authService, roleService);
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
        verify(userRepo, never()).delete(any());
    }

    @Test
    void 不能删除系统身份local() {
        UserEntity local = user("u0", "local", UserEntity.ROLE_ADMIN);
        local.setPasswordHash(null);
        when(userRepo.findById("u0")).thenReturn(Optional.of(local));

        assertThrows(DevMindException.class, () -> service.delete("u0", "admin"));
        verify(userRepo, never()).delete(any());
    }

    @Test
    void 不能删除最后一个可登录ADMIN() {
        UserEntity target = user("u1", "root", UserEntity.ROLE_ADMIN);
        when(userRepo.findById("u1")).thenReturn(Optional.of(target));
        when(userRepo.findAll()).thenReturn(List.of(target));

        assertThrows(DevMindException.class, () -> service.delete("u1", "admin"));
        verify(userRepo, never()).delete(any());
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
        verify(userRepo, never()).delete(any());
        verify(refreshRepo, never()).deleteByUserId(anyString());
    }

    @Test
    void 创建用户按角色编码写入基础权限() {
        RoleEntity qa = role("QA_LEAD", UserEntity.ROLE_VIEWER);
        when(roleService.require("qa_lead")).thenReturn(qa);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateUserRequest("bob", null, null, "secret1", "qa_lead"));

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepo).save(saved.capture());
        assertEquals("QA_LEAD", saved.getValue().getRoleCode());
        assertEquals(UserEntity.ROLE_VIEWER, saved.getValue().getRole());
    }

    @Test
    void 降级最后一个ADMIN仍被拦截_自定义角色按基础权限判定() {
        UserEntity admin = user("u1", "root", UserEntity.ROLE_ADMIN);
        when(userRepo.findById("u1")).thenReturn(Optional.of(admin));
        when(userRepo.findAll()).thenReturn(List.of(admin));
        when(roleService.require("QA_LEAD")).thenReturn(role("QA_LEAD", UserEntity.ROLE_DEVELOPER));

        assertThrows(DevMindException.class, () ->
                service.update("u1", new UpdateUserRequest(null, null, "QA_LEAD", null), "ops"));
        assertEquals(UserEntity.ROLE_ADMIN, admin.getRole());
        assertEquals(UserEntity.ROLE_ADMIN, admin.getRoleCode());
        verify(userRepo, never()).save(any());
    }
}
