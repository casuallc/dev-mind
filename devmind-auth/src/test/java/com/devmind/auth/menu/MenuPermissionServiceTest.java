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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuPermissionServiceTest {

    private final MenuItemRepository menuRepo = mock(MenuItemRepository.class);
    private final RoleMenuGrantRepository grantRepo = mock(RoleMenuGrantRepository.class);
    private final RoleRepository roleRepo = mock(RoleRepository.class);
    private MenuPermissionService service;

    private static final List<RoleEntity> ROLES = List.of(
            role(RoleEntity.CODE_ADMIN, RoleEntity.CODE_ADMIN),
            role(RoleEntity.CODE_DEVELOPER, RoleEntity.CODE_DEVELOPER),
            role(RoleEntity.CODE_VIEWER, RoleEntity.CODE_VIEWER));

    @BeforeEach
    void setUp() {
        service = new MenuPermissionService(menuRepo, grantRepo, roleRepo);
        when(menuRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(grantRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepo.findAllByOrderByBuiltinDescCodeAsc()).thenReturn(ROLES);
        when(roleRepo.findById(anyString())).thenAnswer(inv -> ROLES.stream()
                .filter(role -> role.getCode().equals(inv.getArgument(0)))
                .findFirst());
    }

    private static RoleEntity role(String code, String baseRole) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setName(code);
        role.setBuiltin(true);
        role.setBaseRole(baseRole);
        return role;
    }

    private MenuItemEntity menu(String key, String zone, int sortNo) {
        MenuItemEntity e = new MenuItemEntity();
        e.setMenuKey(key);
        e.setTitle(key);
        e.setPath("/" + key.replace('.', '/'));
        e.setZone(zone);
        e.setSortNo(sortNo);
        return e;
    }

    private RoleMenuGrantEntity grant(String role, String key) {
        RoleMenuGrantEntity g = new RoleMenuGrantEntity();
        g.setRole(role);
        g.setMenuKey(key);
        return g;
    }

    @Test
    void 首次同步落全量目录并按zone补默认授权() {
        when(menuRepo.findAll()).thenReturn(List.of());

        service.syncCatalog();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MenuItemEntity>> menus = ArgumentCaptor.forClass(List.class);
        verify(menuRepo).saveAll(menus.capture());
        assertEquals(MenuCatalog.items().size(), menus.getValue().size());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleMenuGrantEntity>> grants = ArgumentCaptor.forClass(List.class);
        verify(grantRepo).saveAll(grants.capture());
        long nonAdmin = MenuCatalog.items().stream()
                .filter(i -> !MenuItemEntity.ZONE_ADMIN.equals(i.zone())).count();
        long admin = MenuCatalog.items().size() - nonAdmin;
        assertEquals(nonAdmin * 3 + admin, grants.getValue().size());
        assertTrue(grants.getValue().stream().noneMatch(g ->
                MenuItemEntity.ZONE_ADMIN.equals(zoneOf(g.getMenuKey()))
                        && !RoleEntity.CODE_ADMIN.equals(g.getRole())));
    }

    @Test
    void 已有菜单同步不覆盖授权且只给新菜单补种子() {
        when(menuRepo.findAll()).thenReturn(List.of(menu("top.home", MenuItemEntity.ZONE_TOP, 10)));

        service.syncCatalog();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleMenuGrantEntity>> grants = ArgumentCaptor.forClass(List.class);
        verify(grantRepo).saveAll(grants.capture());
        assertTrue(grants.getValue().stream().noneMatch(g -> "top.home".equals(g.getMenuKey())));
        verify(grantRepo, never()).deleteByRole(anyString());
    }

    @Test
    void 目录外菜单标记retired且不物理删除() {
        MenuItemEntity legacy = menu("top.legacy", MenuItemEntity.ZONE_TOP, 99);
        when(menuRepo.findAll()).thenReturn(List.of(legacy));

        service.syncCatalog();

        assertTrue(legacy.isRetired());
        verify(menuRepo, never()).delete(any());
        verify(menuRepo, never()).deleteAll();
    }

    @Test
    void ADMIN短路全量_其他角色取目录与授权交集() {
        when(menuRepo.findAll()).thenReturn(List.of(
                menu("top.home", MenuItemEntity.ZONE_TOP, 10),
                menu("project.tests", MenuItemEntity.ZONE_PROJECT, 70),
                menu("admin.users", MenuItemEntity.ZONE_ADMIN, 190)));
        when(grantRepo.findByRole(RoleEntity.CODE_DEVELOPER)).thenReturn(List.of(
                grant(RoleEntity.CODE_DEVELOPER, "top.home"),
                grant(RoleEntity.CODE_DEVELOPER, "admin.users"),
                grant(RoleEntity.CODE_DEVELOPER, "top.retired")));

        assertEquals(Set.of("top.home", "project.tests", "admin.users"),
                keys(service.visibleMenus(RoleEntity.CODE_ADMIN)));
        assertEquals(Set.of("top.home", "admin.users"),
                keys(service.visibleMenus(RoleEntity.CODE_DEVELOPER)));
        assertTrue(service.visibleMenus(RoleEntity.CODE_VIEWER).isEmpty());
    }

    @Test
    void retired菜单不下发且不进knownPaths() {
        MenuItemEntity retired = menu("top.old", MenuItemEntity.ZONE_TOP, 10);
        retired.setRetired(true);
        when(menuRepo.findAll()).thenReturn(List.of(menu("top.home", MenuItemEntity.ZONE_TOP, 20), retired));

        assertEquals(Set.of("top.home"), keys(service.visibleMenus(RoleEntity.CODE_ADMIN)));
        assertEquals(List.of("/top/home"), service.knownPaths());
    }

    @Test
    void 保存授权拒绝内置ADMIN与不存在的角色() {
        assertThrows(DevMindException.class, () -> service.saveGrants("ADMIN", List.of("top.home")));
        assertThrows(DevMindException.class, () -> service.saveGrants("OWNER", List.of("top.home")));
        verify(grantRepo, never()).deleteByRole(anyString());
    }

    @Test
    void 保存授权拒绝不存在或已下架的菜单() {
        when(menuRepo.findAll()).thenReturn(List.of(menu("top.home", MenuItemEntity.ZONE_TOP, 10)));

        assertThrows(DevMindException.class, () -> service.saveGrants("DEVELOPER", List.of("not.exists")));
        verify(grantRepo, never()).deleteByRole(anyString());
    }

    @Test
    void 保存授权全量覆盖并去重() {
        when(menuRepo.findAll()).thenReturn(List.of(
                menu("top.home", MenuItemEntity.ZONE_TOP, 10),
                menu("project.tests", MenuItemEntity.ZONE_PROJECT, 70)));
        when(grantRepo.findByRole(RoleEntity.CODE_DEVELOPER)).thenReturn(List.of());

        MenuGrantsView view = service.saveGrants("developer", List.of("top.home", "top.home", "project.tests"));

        verify(grantRepo).deleteByRole(RoleEntity.CODE_DEVELOPER);
        verify(grantRepo).flush();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleMenuGrantEntity>> grants = ArgumentCaptor.forClass(List.class);
        verify(grantRepo).saveAll(grants.capture());
        assertEquals(List.of("top.home", "project.tests"),
                grants.getValue().stream().map(RoleMenuGrantEntity::getMenuKey).toList());
        assertTrue(grants.getValue().stream()
                .allMatch(g -> RoleEntity.CODE_DEVELOPER.equals(g.getRole())));
        assertEquals(3, view.roles().size());
        assertFalse(view.roles().contains("OWNER"));
    }

    @Test
    void 自定义角色保存授权后按角色编码下发菜单() {
        RoleEntity qa = role("QA_LEAD", RoleEntity.CODE_DEVELOPER);
        qa.setBuiltin(false);
        when(roleRepo.findAllByOrderByBuiltinDescCodeAsc()).thenReturn(List.of(qa));
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(qa));
        when(menuRepo.findAll()).thenReturn(List.of(
                menu("top.home", MenuItemEntity.ZONE_TOP, 10),
                menu("project.tests", MenuItemEntity.ZONE_PROJECT, 70)));

        MenuGrantsView view = service.saveGrants("qa_lead", List.of("project.tests"));

        assertEquals(List.of("QA_LEAD"), view.roles());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleMenuGrantEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(grantRepo).saveAll(saved.capture());
        assertEquals(List.of("project.tests"),
                saved.getValue().stream().map(RoleMenuGrantEntity::getMenuKey).toList());
        assertTrue(saved.getValue().stream().allMatch(g -> "QA_LEAD".equals(g.getRole())));

        when(grantRepo.findByRole("QA_LEAD")).thenReturn(List.of(grant("QA_LEAD", "project.tests")));
        assertEquals(Set.of("project.tests"), keys(service.visibleMenus("qa_lead")));
    }

    private Set<String> keys(List<MenuItemView> items) {
        return items.stream().map(MenuItemView::menuKey).collect(Collectors.toSet());
    }

    private static String zoneOf(String key) {
        return MenuCatalog.items().stream()
                .filter(i -> i.menuKey().equals(key))
                .map(MenuCatalog.Item::zone)
                .findFirst().orElse("");
    }
}
