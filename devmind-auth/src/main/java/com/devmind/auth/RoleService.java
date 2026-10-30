package com.devmind.auth;

import com.devmind.auth.dto.CreateRoleRequest;
import com.devmind.auth.dto.RoleView;
import com.devmind.auth.dto.UpdateRoleRequest;
import com.devmind.auth.model.MenuItemEntity;
import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.RoleMenuGrantEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.MenuItemRepository;
import com.devmind.auth.repo.RoleMenuGrantRepository;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色管理：内置 ADMIN/DEVELOPER/VIEWER 由启动种子保证存在且不可删改；
 * 自定义角色可选基础权限（DEVELOPER/VIEWER），菜单授权复用 role_menu_grants。
 * users.role 始终保存角色对应的基础权限，供既有过滤器链与数据可见性判断使用。
 */
@Service
public class RoleService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,15}$");
    private static final Set<String> CUSTOM_BASE_ROLES = Set.of(
            RoleEntity.CODE_DEVELOPER, RoleEntity.CODE_VIEWER);
    private static final List<BuiltinRole> BUILTIN_ROLES = List.of(
            new BuiltinRole(RoleEntity.CODE_ADMIN, "管理员", "拥有全部后台接口权限；菜单恒为全部",
                    RoleEntity.CODE_ADMIN),
            new BuiltinRole(RoleEntity.CODE_DEVELOPER, "开发者", "业务读写权限", RoleEntity.CODE_DEVELOPER),
            new BuiltinRole(RoleEntity.CODE_VIEWER, "只读用户", "只读权限", RoleEntity.CODE_VIEWER));

    private record BuiltinRole(String code, String name, String description, String baseRole) {
    }

    private final RoleRepository roleRepo;
    private final UserRepository userRepo;
    private final RoleMenuGrantRepository grantRepo;
    private final MenuItemRepository menuRepo;

    public RoleService(RoleRepository roleRepo, UserRepository userRepo,
                       RoleMenuGrantRepository grantRepo, MenuItemRepository menuRepo) {
        this.roleRepo = roleRepo;
        this.userRepo = userRepo;
        this.grantRepo = grantRepo;
        this.menuRepo = menuRepo;
    }

    /** 启动种子：内置角色存在即校正（编码/基础权限固定），不存在则创建。 */
    @Transactional
    public void seedBuiltinRoles() {
        Instant now = Instant.now();
        for (BuiltinRole builtin : BUILTIN_ROLES) {
            RoleEntity role = roleRepo.findById(builtin.code()).orElseGet(() -> {
                RoleEntity created = new RoleEntity();
                created.setCode(builtin.code());
                created.setCreatedAt(now);
                return created;
            });
            role.setName(builtin.name());
            role.setDescription(builtin.description());
            role.setBuiltin(true);
            role.setBaseRole(builtin.baseRole());
            if (role.getUpdatedAt() == null) {
                role.setUpdatedAt(now);
            }
            roleRepo.save(role);
        }
    }

    public List<RoleView> list() {
        List<MenuItemEntity> active = activeMenus();
        return roleRepo.findAllByOrderByBuiltinDescCodeAsc().stream()
                .map(role -> toView(role, active))
                .toList();
    }

    /** 按编码取角色（大小写不敏感），不存在即 400。用户分配角色时复用。 */
    public RoleEntity require(String code) {
        String normalized = normalizeCode(code, false);
        return roleRepo.findById(normalized)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "角色不存在或已删除: " + code));
    }

    @Transactional
    public RoleView create(CreateRoleRequest req) {
        String code = normalizeCode(req.code(), true);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "角色编码需为 2~16 位大写字母/数字/下划线，且以字母开头（如 QA_LEAD）");
        }
        if (roleRepo.existsById(code)) {
            throw new DevMindException(ErrorCode.CONFLICT, "角色编码已存在: " + code);
        }
        String baseRole = requireCustomBase(req.baseRole());
        Instant now = Instant.now();
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setName(requireName(req.name()));
        role.setDescription(trimToNull(req.description()));
        role.setBuiltin(false);
        role.setBaseRole(baseRole);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleRepo.save(role);
        seedDefaultGrants(code);
        return toView(role, activeMenus());
    }

    @Transactional
    public RoleView update(String code, UpdateRoleRequest req) {
        RoleEntity role = require(code);
        if (role.isBuiltin()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "内置角色不可编辑");
        }
        if (req.name() != null) {
            role.setName(requireName(req.name()));
        }
        if (req.description() != null) {
            role.setDescription(trimToNull(req.description()));
        }
        if (req.baseRole() != null && !req.baseRole().isBlank()) {
            String baseRole = requireCustomBase(req.baseRole());
            if (!baseRole.equals(role.getBaseRole())) {
                List<UserEntity> users = userRepo.findByRoleCode(role.getCode());
                users.forEach(user -> user.setRole(baseRole));
                userRepo.saveAll(users);
                role.setBaseRole(baseRole);
            }
        }
        role.setUpdatedAt(Instant.now());
        roleRepo.save(role);
        return toView(role, activeMenus());
    }

    @Transactional
    public void delete(String code) {
        RoleEntity role = require(code);
        if (role.isBuiltin()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "内置角色不可删除");
        }
        long userCount = userRepo.countByRoleCode(role.getCode());
        if (userCount > 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "角色「" + role.getName() + "」仍有 " + userCount + " 个用户，请先调整用户角色");
        }
        grantRepo.deleteByRole(role.getCode());
        grantRepo.flush();
        roleRepo.delete(role);
    }

    private RoleView toView(RoleEntity role, List<MenuItemEntity> active) {
        Set<String> activeKeys = new LinkedHashSet<>();
        active.forEach(menu -> activeKeys.add(menu.getMenuKey()));
        List<String> menuKeys;
        if (RoleEntity.CODE_ADMIN.equals(role.getCode())) {
            menuKeys = new ArrayList<>(activeKeys);
        } else {
            menuKeys = grantRepo.findByRole(role.getCode()).stream()
                    .map(RoleMenuGrantEntity::getMenuKey)
                    .filter(activeKeys::contains)
                    .distinct()
                    .toList();
        }
        return new RoleView(role.getCode(), role.getName(), role.getDescription(), role.isBuiltin(),
                role.getBaseRole(), userRepo.countByRoleCode(role.getCode()), menuKeys,
                role.getCreatedAt(), role.getUpdatedAt());
    }

    private void seedDefaultGrants(String roleCode) {
        Instant now = Instant.now();
        List<RoleMenuGrantEntity> grants = activeMenus().stream()
                .filter(menu -> !MenuItemEntity.ZONE_ADMIN.equals(menu.getZone()))
                .map(menu -> {
                    RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
                    grant.setRole(roleCode);
                    grant.setMenuKey(menu.getMenuKey());
                    grant.setCreatedAt(now);
                    return grant;
                })
                .toList();
        if (!grants.isEmpty()) {
            grantRepo.saveAll(grants);
        }
    }

    private List<MenuItemEntity> activeMenus() {
        return menuRepo.findAll().stream().filter(menu -> !menu.isRetired()).toList();
    }

    private static String requireCustomBase(String baseRole) {
        String normalized = baseRole == null ? "" : baseRole.trim().toUpperCase(Locale.ROOT);
        if (!CUSTOM_BASE_ROLES.contains(normalized)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "基础权限仅支持 DEVELOPER（读写）或 VIEWER（只读）；ADMIN 仅内置管理员角色");
        }
        return normalized;
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        return trimmed;
    }

    private static String normalizeCode(String code, boolean allowEmpty) {
        String raw = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (raw.isEmpty() && !allowEmpty) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "角色编码不能为空");
        }
        return raw;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
