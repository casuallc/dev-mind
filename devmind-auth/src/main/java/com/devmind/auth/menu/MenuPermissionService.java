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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-61 权限目录同步与角色可见性判定；CAP-102 起角色清单来自 roles 表（支持自定义角色）；
 * CAP-103 扩展为 MENU/TAB/ACTION 三级，子节点授权继承父节点、保存时补齐祖先链。
 * 菜单/页签是展示层管控；ACTION 中登记了服务端规则的写操作由 {@link ApiPermissionFilter} 独立拦截。
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
     * FR-01/FR-02 启动同步：新增节点落库 + 补默认授权，下架节点标 retired（不物理删），
     * 已有授权一律不覆盖（管理员后续调整优先）。
     * 新页签/按钮默认复制父节点当前授权；父节点也是本次新增时用本次种子结果。
     */
    @Transactional
    public void syncCatalog() {
        List<MenuItemEntity> existing = menuRepo.findAll();
        Map<String, MenuItemEntity> byKey = existing.stream()
                .collect(Collectors.toMap(MenuItemEntity::getMenuKey, Function.identity()));
        Map<String, Set<String>> grantsByKey = new HashMap<>();
        for (RoleMenuGrantEntity grant : grantRepo.findAll()) {
            grantsByKey.computeIfAbsent(grant.getMenuKey(), k -> new LinkedHashSet<>()).add(grant.getRole());
        }

        Set<String> catalogKeys = new LinkedHashSet<>();
        Map<String, MenuCatalog.Item> catalogByKey = new LinkedHashMap<>();
        List<MenuItemEntity> toSave = new ArrayList<>();
        List<RoleMenuGrantEntity> grantSeeds = new ArrayList<>();
        int created = 0;

        for (MenuCatalog.Item item : MenuCatalog.items()) {
            catalogKeys.add(item.menuKey());
            catalogByKey.put(item.menuKey(), item);
            MenuCatalog.Item parent = item.parentKey() == null ? null : catalogByKey.get(item.parentKey());
            if (item.parentKey() != null && parent == null) {
                throw new IllegalStateException("权限目录父节点未先登记: " + item.menuKey());
            }
            String zone = parent == null ? item.zone() : parent.zone();
            MenuItemEntity entity = byKey.get(item.menuKey());
            if (entity == null) {
                entity = new MenuItemEntity();
                entity.setMenuKey(item.menuKey());
                entity.setCreatedAt(Instant.now());
                created++;
                Set<String> targetRoles = parent == null
                        ? defaultRoles(zone)
                        : inheritedRoles(grantsByKey, item.parentKey(), zone);
                grantSeeds.addAll(buildGrants(item.menuKey(), targetRoles));
                grantsByKey.put(item.menuKey(), targetRoles);
            }
            entity.setTitle(item.title());
            entity.setPath(item.path());
            entity.setZone(zone);
            entity.setGroupName(item.groupName());
            entity.setIcon(item.icon());
            entity.setSortNo(item.sortNo());
            entity.setNodeType(item.nodeType());
            entity.setParentKey(item.parentKey());
            entity.setWriteAction(item.writeAction());
            entity.setRetired(false);
            toSave.add(entity);
        }

        int retired = 0;
        for (MenuItemEntity entity : existing) {
            if (!catalogKeys.contains(entity.getMenuKey())) {
                if (entity.getNodeType() == null) {
                    entity.setNodeType(MenuItemEntity.TYPE_MENU);
                }
                if (!entity.isRetired()) {
                    entity.setRetired(true);
                    toSave.add(entity);
                    retired++;
                }
            }
        }

        menuRepo.saveAll(toSave);
        if (!grantSeeds.isEmpty()) {
            grantRepo.saveAll(grantSeeds);
        }
        if (created > 0 || retired > 0) {
            log.info("CAP-103 权限目录同步：新增 {} 项，下架 {} 项，默认授权 {} 条",
                    created, retired, grantSeeds.size());
        }
    }

    /** 全量目录（含 retired，供管理授权树展示）；按 zone → sortNo 排序。 */
    public List<MenuItemView> catalog() {
        return menuRepo.findAll().stream().sorted(CATALOG_ORDER).map(MenuPermissionService::toView).toList();
    }

    /** FR-03 判定链：内置 ADMIN 短路全量在架目录；其他角色取目录 ∩ 授权（子节点授权隐含祖先）。 */
    public List<MenuItemView> visibleMenus(String roleCode) {
        List<MenuItemView> active = catalog().stream().filter(m -> !m.retired()).toList();
        String code = normalize(roleCode);
        if (RoleEntity.CODE_ADMIN.equals(code)) {
            return active;
        }
        if (code.isEmpty()) {
            return List.of();
        }
        Set<String> granted = grantRepo.findByRole(code).stream()
                .map(RoleMenuGrantEntity::getMenuKey)
                .collect(Collectors.toSet());
        Map<String, MenuItemView> byKey = active.stream()
                .collect(Collectors.toMap(MenuItemView::menuKey, Function.identity()));
        Set<String> visible = new HashSet<>();
        for (String key : granted) {
            String cursor = key;
            while (cursor != null && visible.add(cursor)) {
                MenuItemView node = byKey.get(cursor);
                cursor = node == null ? null : node.parentKey();
            }
        }
        return active.stream().filter(m -> visible.contains(m.menuKey())).toList();
    }

    /** 在架菜单路径全集（弱守卫用，不分角色）；页签/按钮没有路由，不参与。 */
    public List<String> knownPaths() {
        return catalog().stream()
                .filter(m -> !m.retired())
                .filter(m -> MenuItemEntity.TYPE_MENU.equals(m.nodeType()))
                .map(MenuItemView::path)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
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

    /** FR-04 全量覆盖某角色授权：先删后插，幂等；自动补齐每个授权节点的祖先链。 */
    @Transactional
    public MenuGrantsView saveGrants(String role, List<String> menuKeys) {
        String normalized = normalize(role);
        RoleEntity roleEntity = roleRepo.findById(normalized)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "角色不存在或已删除: " + role));
        if (RoleEntity.CODE_ADMIN.equals(roleEntity.getCode())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "内置 ADMIN 角色恒见全部权限，不支持调整授权");
        }
        Map<String, String> activeParents = new HashMap<>();
        catalog().stream().filter(m -> !m.retired())
                .forEach(m -> activeParents.put(m.menuKey(), m.parentKey()));
        List<String> target = menuKeys == null ? List.of() : menuKeys.stream()
                .filter(k -> k != null && !k.isBlank()).map(String::trim).distinct().toList();
        List<String> unknown = target.stream().filter(k -> !activeParents.containsKey(k)).toList();
        if (!unknown.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "权限节点不存在或已下架: " + String.join(", ", unknown));
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String key : target) {
            String cursor = key;
            while (cursor != null && expanded.add(cursor)) {
                cursor = activeParents.get(cursor);
            }
        }
        grantRepo.deleteByRole(normalized);
        grantRepo.flush();
        Instant now = Instant.now();
        grantRepo.saveAll(expanded.stream().map(key -> {
            RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
            grant.setRole(normalized);
            grant.setMenuKey(key);
            grant.setCreatedAt(now);
            return grant;
        }).toList());
        return grants();
    }

    /** CAP-103 后端拦截判定：ADMIN 短路；任一权限编码被授予即放行。 */
    public boolean hasAnyGrant(String roleCode, Collection<String> permissionKeys) {
        String code = normalize(roleCode);
        if (RoleEntity.CODE_ADMIN.equals(code)) {
            return true;
        }
        if (code.isEmpty() || permissionKeys == null || permissionKeys.isEmpty()) {
            return false;
        }
        return grantRepo.existsByRoleAndMenuKeyIn(code, permissionKeys);
    }

    private Set<String> inheritedRoles(Map<String, Set<String>> grantsByKey, String parentKey, String zone) {
        Set<String> inherited = grantsByKey.get(parentKey);
        return inherited == null || inherited.isEmpty() ? defaultRoles(zone) : new LinkedHashSet<>(inherited);
    }

    private List<RoleMenuGrantEntity> buildGrants(String menuKey, Collection<String> targetRoles) {
        Instant now = Instant.now();
        return targetRoles.stream().map(role -> {
            RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
            grant.setRole(role);
            grant.setMenuKey(menuKey);
            grant.setCreatedAt(now);
            return grant;
        }).toList();
    }

    private Set<String> defaultRoles(String zone) {
        return MenuItemEntity.ZONE_ADMIN.equals(zone)
                ? new LinkedHashSet<>(List.of(RoleEntity.CODE_ADMIN))
                : new LinkedHashSet<>(roles());
    }

    private List<String> roles() {
        return roleRepo.findAllByOrderByBuiltinDescCodeAsc().stream()
                .map(RoleEntity::getCode).toList();
    }

    private static MenuItemView toView(MenuItemEntity e) {
        String nodeType = e.getNodeType() == null ? MenuItemEntity.TYPE_MENU : e.getNodeType();
        return new MenuItemView(e.getMenuKey(), e.getTitle(), e.getPath(), e.getZone(),
                e.getGroupName(), e.getIcon(), e.getSortNo(), e.isRetired(),
                nodeType, e.getParentKey(), e.isWriteAction());
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
