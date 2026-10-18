package com.devmind.copilot.service;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * CAP-76 FR-18：角色必须从用户表实时解析，未知角色按 VIEWER（fail-closed）；
 * 用户不存在/停用 → 禁止所有写工具。
 */
@Service
public class CopilotIdentityService {

    /** active=false 时读工具仍可按 VIEWER 走，写工具一律拒绝。 */
    public record Identity(String actor, String role, boolean active) {
    }

    private final UserRepository users;

    public CopilotIdentityService(UserRepository users) {
        this.users = users;
    }

    public Identity resolve(String actor) {
        if (actor == null || actor.isBlank()) {
            return new Identity(actor, UserEntity.ROLE_VIEWER, false);
        }
        Optional<UserEntity> found = users.findByUsername(actor);
        if (found.isEmpty()) {
            return new Identity(actor, UserEntity.ROLE_VIEWER, false);
        }
        UserEntity user = found.get();
        String role = normalizeRole(user.getRole());
        return new Identity(actor, role, user.isActive());
    }

    public boolean active(String actor) {
        return resolve(actor).active();
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return UserEntity.ROLE_VIEWER;
        }
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case UserEntity.ROLE_ADMIN -> UserEntity.ROLE_ADMIN;
            case UserEntity.ROLE_DEVELOPER -> UserEntity.ROLE_DEVELOPER;
            default -> UserEntity.ROLE_VIEWER;
        };
    }
}
