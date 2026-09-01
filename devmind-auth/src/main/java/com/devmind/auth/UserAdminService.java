package com.devmind.auth;

import com.devmind.auth.dto.CreateUserRequest;
import com.devmind.auth.dto.UpdateUserRequest;
import com.devmind.auth.dto.UserView;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * CAP-01 FR-06 用户管理（仅 ADMIN，过滤器链控制）。防锁死：不允许禁用/降级最后一个可登录 ADMIN，
 * 不允许禁用自己。
 */
@Service
public class UserAdminService {

    private static final Set<String> ROLES = Set.of(UserEntity.ROLE_ADMIN, UserEntity.ROLE_DEVELOPER,
            UserEntity.ROLE_VIEWER);
    private static final Set<String> STATUSES = Set.of(UserEntity.STATUS_ACTIVE, UserEntity.STATUS_DISABLED);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public UserAdminService(UserRepository userRepo, PasswordEncoder passwordEncoder, AuthService authService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    public List<UserView> list() {
        return userRepo.findAll().stream().map(authService::toView).toList();
    }

    @Transactional
    public UserView create(CreateUserRequest req) {
        String username = req.username().trim();
        if (userRepo.findByUsername(username).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "用户名已存在: " + username);
        }
        UserEntity u = new UserEntity();
        u.setId(shortId());
        u.setUsername(username);
        u.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? username : req.displayName().trim());
        u.setRole(requireRole(req.role()));
        u.setStatus(UserEntity.STATUS_ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setCreatedAt(Instant.now());
        return authService.toView(userRepo.save(u));
    }

    @Transactional
    public UserView update(String id, UpdateUserRequest req, String operator) {
        UserEntity u = require(id);
        if (req.displayName() != null && !req.displayName().isBlank()) {
            u.setDisplayName(req.displayName().trim());
        }
        if (req.role() != null && !req.role().isBlank()) {
            String role = requireRole(req.role());
            guardLastAdmin(u, role, u.getStatus());
            u.setRole(role);
        }
        if (req.status() != null && !req.status().isBlank()) {
            String status = req.status().trim().toUpperCase();
            if (!STATUSES.contains(status)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "非法状态: " + status);
            }
            if (UserEntity.STATUS_DISABLED.equals(status) && u.getUsername().equals(operator)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "不能禁用当前登录账号");
            }
            guardLastAdmin(u, u.getRole(), status);
            u.setStatus(status);
        }
        return authService.toView(userRepo.save(u));
    }

    @Transactional
    public void resetPassword(String id, String newPassword) {
        UserEntity u = require(id);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(u);
    }

    /** 自助改密码：校验旧密码。 */
    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserEntity u = userRepo.findByUsername(username)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        if (u.getPasswordHash() == null || !passwordEncoder.matches(oldPassword, u.getPasswordHash())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "原密码错误");
        }
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepo.save(u);
    }

    private UserEntity require(String id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "用户不存在: " + id));
    }

    private String requireRole(String role) {
        String r = role.trim().toUpperCase();
        if (!ROLES.contains(r)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法角色: " + role + "（可选 ADMIN/DEVELOPER/VIEWER）");
        }
        return r;
    }

    /** 目标用户若正从「可登录 ADMIN」变成不是，要求系统里还剩另一个可登录 ADMIN */
    private void guardLastAdmin(UserEntity target, String newRole, String newStatus) {
        boolean wasLoginAdmin = UserEntity.ROLE_ADMIN.equals(target.getRole())
                && target.getPasswordHash() != null && target.isActive();
        boolean willBeLoginAdmin = UserEntity.ROLE_ADMIN.equals(newRole)
                && target.getPasswordHash() != null && UserEntity.STATUS_ACTIVE.equals(newStatus);
        if (!wasLoginAdmin || willBeLoginAdmin) {
            return;
        }
        boolean anotherAdmin = userRepo.findAll().stream()
                .anyMatch(x -> !x.getId().equals(target.getId())
                        && UserEntity.ROLE_ADMIN.equals(x.getRole())
                        && x.getPasswordHash() != null && x.isActive());
        if (!anotherAdmin) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "系统至少保留一个可用的 ADMIN 账号");
        }
    }

    private String shortId() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
