package com.devmind.auth.menu;

import com.devmind.auth.dto.MenuGrantsView;
import com.devmind.auth.dto.MenuItemView;
import com.devmind.auth.model.MenuItemEntity;
import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.RoleMenuGrantEntity;
import com.devmind.auth.repo.MenuItemRepository;
import com.devmind.auth.repo.RoleMenuGrantRepository;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-61 菜单目录同步与角色可见性判定；CAP-102 起角色清单来自 roles 表（支持自定义角色）。
 * 菜单权限是展示层管控，不是安全边界：后端接口鉴权仍由 CAP-01 过滤器链独立成立。
 */
@Service
public class MenuPermissionService {

    private static final Logger log = LoggerFactory.getLogger(MenuPermissionService.class);
    private static final Comparator<MenuItemEntity> CATALOG_ORDER =
            Comparator.comparingInt((MenuItemEntity e) -> zoneRank(e.getZone()))
                    .thenComparingInt(MenuItemEntity::getSortNo)
                    .thenComparing(MenuItemEntity::getId, Comparator.nullsLast(Long::compareTo));

    private final MenuItemRepository menuRepo;
    private final RoleMenuGrantRepository grantRepo;
    private final RoleRepository roleRepo;

    public MenuPermissionService(MenuItemRepository menuRepo, RoleMenuGrantRepository grantRepo,
                                 RoleRepository roleRepo) {
        this.menuRepo = menuRepo;
        this.grantRepo = grantRepo;
        this.roleRepo = roleRepo;
    }

    /**
     * FR-01/FR-02 启动同步：新增菜单落库 + 补默认授权，下架菜单标 retired（不物理删），
     * 已有授权一律不覆盖（管理员后续调整优先）。
     */
    @Transactional
    public void syncCatalog() {
        List<MenuItemEntity> existing = menuRepo.findAll();
        Map<String, MenuItemEntity> byKey = existing.stream()
                .collect(Collectors.toMap(MenuItemEntity::getMenuKey, Function.identity()));
        Set<String> catalogKeys = new LinkedHashSet<>();
        List<MenuItemEntity> toSave = new ArrayList<>();
        List<RoleMenuGrantEntity> grantSeeds = new ArrayList<>();
        int created = 0;

        for (MenuCatalog.Item item : MenuCatalog.items()) {
            catalogKeys.add(item.menuKey());
            MenuItemEntity entity = byKey.get(item.menuKey());
            if (entity == null) {
                entity = new MenuItemEntity();
                entity.setMenuKey(item.menuKey());
                entity.setCreatedAt(Instant.now());
                created++;
                grantSeeds.addAll(defaultGrants(item));
            }
            entity.setTitle(item.title());
            entity.setPath(item.path());
            entity.setZone(item.zone());
            entity.setGroupName(item.groupName());
            entity.setIcon(item.icon());
            entity.setSortNo(item.sortNo());
            entity.setRetired(false);
            toSave.add(entity);
        }

        int retired = 0;
        for (MenuItemEntity entity : existing) {
            if (!catalogKeys.contains(entity.getMenuKey()) && !entity.isRetired()) {
                entity.setRetired(true);
                toSave.add(entity);
                retired++;
            }
        }

        menuRepo.saveAll(toSave);
        if (!grantSeeds.isEmpty()) {
            grantRepo.saveAll(grantSeeds);
        }
        if (created > 0 || retired > 0) {
            log.info("CAP-61 菜单目录同步：新增 {} 项，下架 {} 项，默认授权 {} 条",
                    created, retired, grantSeeds.size());
        }
    }

    /** 全量目录（含 retired，供管理矩阵展示）；按 zone → sortNo 排序。 */
    public List<MenuItemView> catalog() {
        return menuRepo.findAll().stream().sorted(CATALOG_ORDER).map(this::toView).toList();
    }

    /** FR-03 判定链：内置 ADMIN 短路全量在架目录；其他角色取目录 ∩ 授权。 */
    public List<MenuItemView> visibleMenus(String roleCode) {
        List<MenuItemView> active = catalog().stream().filter(m -> !m.retired()).toList();
        if (RoleEntity.CODE_ADMIN.equals(normalize(roleCode))) {
            return active;
        }
        String code = normalize(roleCode);
        if (code.isEmpty()) {
            return List.of();
        }
        Set<String> granted = grantRepo.findByRole(code).stream()
                .map(RoleMenuGrantEntity::getMenuKey)
                .collect(Collectors.toSet());
        return active.stream().filter(m -> granted.contains(m.menuKey())).toList();
    }

    /** 在架菜单路径全集（弱守卫用，不分角色）。 */
    public List<String> knownPaths() {
        return catalog().stream().filter(m -> !m.retired()).map(MenuItemView::path).distinct().toList();
    }

    public MenuGrantsView grants() {
        List<String> roles = roles();
        Map<String, Set<String>> byRole = new LinkedHashMap<>();
        for (String role : roles) {
            byRole.put(role, new LinkedHashSet<>());
        }
        for (RoleMenuGrantEntity grant : grantRepo.findAll()) {
            Set<String> keys = byRole.get(grant.getRole());
            if (keys != null) {
                keys.add(grant.getMenuKey());
            }
        }
        // ADMIN 恒全量（短路矩阵），显式回全量使管理页有权威对照
        byRole.put(RoleEntity.CODE_ADMIN, catalog().stream().filter(m -> !m.retired())
                .map(MenuItemView::menuKey).collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<String, List<String>> grants = new LinkedHashMap<>();
        byRole.forEach((role, keys) -> grants.put(role, new ArrayList<>(keys)));
        return new MenuGrantsView(roles, grants);
    }

    /** FR-04 全量覆盖某角色授权：先删后插，幂等；内置 ADMIN 恒全见不允许修改。 */
    @Transactional
    public MenuGrantsView saveGrants(String role, List<String> menuKeys) {
        String normalized = normalize(role);
        RoleEntity roleEntity = roleRepo.findById(normalized)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "角色不存在或已删除: " + role));
        if (RoleEntity.CODE_ADMIN.equals(roleEntity.getCode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "内置 ADMIN 角色恒见全部菜单，不支持调整授权");
        }
        Set<String> activeKeys = catalog().stream().filter(m -> !m.retired())
                .map(MenuItemView::menuKey).collect(Collectors.toSet());
        List<String> target = menuKeys == null ? List.of() : menuKeys.stream()
                .filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        List<String> unknown = target.stream().filter(k -> !activeKeys.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "菜单不存在或已下架: " + String.join(", ", unknown));
        }
        grantRepo.deleteByRole(normalized);
        grantRepo.flush();
        Instant now = Instant.now();
        grantRepo.saveAll(target.stream().map(key -> {
            RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
            grant.setRole(normalized);
            grant.setMenuKey(key);
            grant.setCreatedAt(now);
            return grant;
        }).toList());
        return grants();
    }

    private List<RoleMenuGrantEntity> defaultGrants(MenuCatalog.Item item) {
        List<String> roles = roles();
        List<String> targetRoles = MenuItemEntity.ZONE_ADMIN.equals(item.zone())
                ? List.of(RoleEntity.CODE_ADMIN)
                : roles;
        Instant now = Instant.now();
        return targetRoles.stream().map(role -> {
            RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
            grant.setRole(role);
            grant.setMenuKey(item.menuKey());
            grant.setCreatedAt(now);
            return grant;
        }).toList();
    }

    private List<String> roles() {
        return roleRepo.findAllByOrderByBuiltinDescCodeAsc().stream()
                .map(RoleEntity::getCode).toList();
    }

    private MenuItemView toView(MenuItemEntity e) {
        return new MenuItemView(e.getMenuKey(), e.getTitle(), e.getPath(), e.getZone(),
                e.getGroupName(), e.getIcon(), e.getSortNo(), e.isRetired());
    }

    private static String normalize(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    private static int zoneRank(String zone) {
        return switch (zone == null ? "" : zone) {
            case MenuItemEntity.ZONE_TOP -> 0;
            case MenuItemEntity.ZONE_PROJECT -> 1;
            case MenuItemEntity.ZONE_ADMIN -> 2;
            default -> 9;
        };
    }
}
