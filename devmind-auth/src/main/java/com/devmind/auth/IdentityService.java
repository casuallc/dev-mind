package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 身份服务（CAP-01）：给所有模块一个稳定的「当前操作者」。
 * 登录接入后 {@link #currentActor()} 从 SecurityContext 取真实用户，无认证上下文回退 local；
 * 同时负责 users 种子/迁移。各业务表的 created_by 写入点不变。
 */
@Service
public class IdentityService {

    public static final String LOCAL_USER = "local";

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;

    public IdentityService(UserRepository userRepo,
                           PasswordEncoder passwordEncoder, AuthProperties props) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @PostConstruct
    void seed() {
        migrateLegacyRoles();
        if (userRepo.findByUsername(LOCAL_USER).isEmpty()) {
            UserEntity u = new UserEntity();
            u.setId(shortId());
            u.setUsername(LOCAL_USER);
            u.setDisplayName("本机用户");
            u.setRole(UserEntity.ROLE_ADMIN);
            u.setStatus(UserEntity.STATUS_ACTIVE);
            u.setCreatedAt(Instant.now());
            userRepo.save(u);
            log.info("种子用户已创建: local（系统身份，无密码不可登录）");
        }
        // 首次启动：无任何「可登录的 ADMIN」时种子 admin（初始密码见配置 devmind.auth.admin-password）
        boolean hasLoginAdmin = userRepo.findAll().stream()
                .anyMatch(u -> UserEntity.ROLE_ADMIN.equals(u.getRole()) && u.getPasswordHash() != null);
        if (!hasLoginAdmin) {
            UserEntity admin = new UserEntity();
            admin.setId(shortId());
            admin.setUsername("admin");
            admin.setDisplayName("管理员");
            admin.setRole(UserEntity.ROLE_ADMIN);
            admin.setStatus(UserEntity.STATUS_ACTIVE);
            admin.setPasswordHash(passwordEncoder.encode(props.getAdminPassword()));
            admin.setCreatedAt(Instant.now());
            userRepo.save(admin);
            log.warn("已创建初始管理员 admin（密码见配置 devmind.auth.admin-password，默认 admin123）——请登录后立即修改！");
        }
    }

    /** 旧枚举值迁移：OWNER→ADMIN、MEMBER→DEVELOPER（角色为 String 列，直接改值） */
    private void migrateLegacyRoles() {
        for (UserEntity u : userRepo.findAll()) {
            String mapped = switch (u.getRole() == null ? "" : u.getRole()) {
                case "OWNER" -> UserEntity.ROLE_ADMIN;
                case "MEMBER" -> UserEntity.ROLE_DEVELOPER;
                case "" -> UserEntity.ROLE_VIEWER;
                default -> null;
            };
            if (mapped != null) {
                u.setRole(mapped);
                if (u.getStatus() == null) {
                    u.setStatus(UserEntity.STATUS_ACTIVE);
                }
                userRepo.save(u);
                log.info("用户角色已迁移: {} -> {}", u.getUsername(), mapped);
            }
        }
    }

    /**
     * 当前操作者标识（写各表 created_by 用）。
     * 请求线程：SecurityContext 中的真实用户；异步线程/事件监听/启动种子：回退 local。
     */
    public String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DevMindPrincipal p) {
            return p.username();
        }
        return LOCAL_USER;
    }

    public Optional<UserEntity> currentUser() {
        return userRepo.findByUsername(currentActor());
    }

    private String shortId() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
