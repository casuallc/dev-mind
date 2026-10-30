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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceTest {

    private final RoleRepository roleRepo = mock(RoleRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final RoleMenuGrantRepository grantRepo = mock(RoleMenuGrantRepository.class);
    private final MenuItemRepository menuRepo = mock(MenuItemRepository.class);
    private RoleService service;

    private static final List<MenuItemEntity> MENUS = List.of(
            menu("top.home", MenuItemEntity.ZONE_TOP, 10),
            menu("project.tests", MenuItemEntity.ZONE_PROJECT, 70),
            menu("admin.users", MenuItemEntity.ZONE_ADMIN, 190));

    @BeforeEach
    void setUp() {
        service = new RoleService(roleRepo, userRepo, grantRepo, menuRepo);
        when(menuRepo.findAll()).thenReturn(MENUS);
        when(roleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(grantRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MenuItemEntity menu(String key, String zone, int sortNo) {
        MenuItemEntity e = new MenuItemEntity();
        e.setMenuKey(key);
        e.setTitle(key);
        e.setPath("/" + key.replace('.', '/'));
        e.setZone(zone);
        e.setSortNo(sortNo);
        return e;
    }

    private static RoleEntity role(String code, boolean builtin, String baseRole) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setName(code);
        role.setBuiltin(builtin);
        role.setBaseRole(baseRole);
        return role;
    }

    private static RoleMenuGrantEntity grant(String role, String key) {
        RoleMenuGrantEntity grant = new RoleMenuGrantEntity();
        grant.setRole(role);
        grant.setMenuKey(key);
        return grant;
    }

    private static UserEntity user(String id, String roleCode, String baseRole) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(id);
        user.setRole(baseRole);
        user.setRoleCode(roleCode);
        return user;
    }

    @Test
    void 种子写入三个内置角色并校正不可变字段() {
        RoleEntity hacked = role("ADMIN", false, RoleEntity.CODE_VIEWER);
        hacked.setName("被改坏的管理员");
        when(roleRepo.findById(RoleEntity.CODE_ADMIN)).thenReturn(Optional.of(hacked));
        when(roleRepo.findById(RoleEntity.CODE_DEVELOPER)).thenReturn(Optional.empty());
        when(roleRepo.findById(RoleEntity.CODE_VIEWER)).thenReturn(Optional.empty());

        service.seedBuiltinRoles();

        ArgumentCaptor<RoleEntity> saved = ArgumentCaptor.forClass(RoleEntity.class);
        verify(roleRepo, times(3)).save(saved.capture());
        assertEquals(3, saved.getAllValues().stream().map(RoleEntity::getCode).distinct().count());
        assertTrue(saved.getAllValues().stream().allMatch(RoleEntity::isBuiltin));
        assertTrue(saved.getAllValues().stream().anyMatch(r ->
                RoleEntity.CODE_ADMIN.equals(r.getCode())
                        && "管理员".equals(r.getName())
                        && RoleEntity.CODE_ADMIN.equals(r.getBaseRole())));
    }

    @Test
    void 创建自定义角色按非后台菜单补默认授权() {
        when(roleRepo.existsById("QA_LEAD")).thenReturn(false);
        when(grantRepo.findByRole("QA_LEAD")).thenReturn(List.of());
        when(userRepo.countByRoleCode("QA_LEAD")).thenReturn(0L);

        RoleView view = service.create(new CreateRoleRequest("qa_lead", " 测试负责人 ", "质量保障", "viewer"));

        assertEquals("QA_LEAD", view.code());
        assertEquals("测试负责人", view.name());
        assertEquals(RoleEntity.CODE_VIEWER, view.baseRole());
        assertFalse(view.builtin());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoleMenuGrantEntity>> grants = ArgumentCaptor.forClass(List.class);
        verify(grantRepo).saveAll(grants.capture());
        assertEquals(List.of("top.home", "project.tests"),
                grants.getValue().stream().map(RoleMenuGrantEntity::getMenuKey).toList());
        assertTrue(grants.getValue().stream().allMatch(g -> "QA_LEAD".equals(g.getRole())));
    }

    @Test
    void 创建角色拒绝非法编码_重名与ADMIN基础权限() {
        assertThrows(DevMindException.class, () ->
                service.create(new CreateRoleRequest("qa-lead", "x", null, "VIEWER")));
        assertThrows(DevMindException.class, () ->
                service.create(new CreateRoleRequest("QA", "x", null, "ADMIN")));

        when(roleRepo.existsById("QA_LEAD")).thenReturn(true);
        assertThrows(DevMindException.class, () ->
                service.create(new CreateRoleRequest("QA_LEAD", "x", null, "VIEWER")));
    }

    @Test
    void 修改基础权限同步该角色下所有用户() {
        RoleEntity qa = role("QA_LEAD", false, RoleEntity.CODE_VIEWER);
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(qa));
        UserEntity u1 = user("u1", "QA_LEAD", RoleEntity.CODE_VIEWER);
        UserEntity u2 = user("u2", "QA_LEAD", RoleEntity.CODE_VIEWER);
        when(userRepo.findByRoleCode("QA_LEAD")).thenReturn(List.of(u1, u2));
        when(userRepo.countByRoleCode("QA_LEAD")).thenReturn(2L);
        when(grantRepo.findByRole("QA_LEAD")).thenReturn(List.of());

        RoleView view = service.update("qa_lead", new UpdateRoleRequest("质量负责人", "新描述", "DEVELOPER"));

        assertEquals(RoleEntity.CODE_DEVELOPER, u1.getRole());
        assertEquals(RoleEntity.CODE_DEVELOPER, u2.getRole());
        assertEquals("QA_LEAD", u1.getRoleCode());
        verify(userRepo).saveAll(List.of(u1, u2));
        assertEquals("质量负责人", view.name());
        assertEquals(RoleEntity.CODE_DEVELOPER, view.baseRole());
    }

    @Test
    void 内置角色不可编辑或删除() {
        RoleEntity admin = role(RoleEntity.CODE_ADMIN, true, RoleEntity.CODE_ADMIN);
        when(roleRepo.findById(RoleEntity.CODE_ADMIN)).thenReturn(Optional.of(admin));

        assertThrows(DevMindException.class, () ->
                service.update("ADMIN", new UpdateRoleRequest("x", null, null)));
        assertThrows(DevMindException.class, () -> service.delete("ADMIN"));
        verify(roleRepo, never()).delete(any());
        verify(roleRepo, never()).save(admin);
    }

    @Test
    void 仍有用户占用的自定义角色不可删除() {
        RoleEntity qa = role("QA_LEAD", false, RoleEntity.CODE_DEVELOPER);
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(qa));
        when(userRepo.countByRoleCode("QA_LEAD")).thenReturn(2L);

        assertThrows(DevMindException.class, () -> service.delete("QA_LEAD"));
        verify(grantRepo, never()).deleteByRole(anyString());
        verify(roleRepo, never()).delete(qa);
    }

    @Test
    void 删除空闲自定义角色连带清理菜单授权() {
        RoleEntity qa = role("QA_LEAD", false, RoleEntity.CODE_DEVELOPER);
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(qa));
        when(userRepo.countByRoleCode("QA_LEAD")).thenReturn(0L);

        service.delete("qa_lead");

        verify(grantRepo).deleteByRole("QA_LEAD");
        verify(grantRepo).flush();
        verify(roleRepo).delete(qa);
    }

    @Test
    void 角色列表ADMIN恒全量_自定义角色取授权交集() {
        RoleEntity admin = role(RoleEntity.CODE_ADMIN, true, RoleEntity.CODE_ADMIN);
        RoleEntity qa = role("QA_LEAD", false, RoleEntity.CODE_DEVELOPER);
        when(roleRepo.findAllByOrderByBuiltinDescCodeAsc()).thenReturn(List.of(admin, qa));
        when(grantRepo.findByRole("QA_LEAD")).thenReturn(List.of(
                grant("QA_LEAD", "top.home"), grant("QA_LEAD", "retired.menu")));
        when(userRepo.countByRoleCode(anyString())).thenReturn(0L);

        List<RoleView> views = service.list();

        assertEquals(List.of("ADMIN", "QA_LEAD"), views.stream().map(RoleView::code).toList());
        assertEquals(List.of("top.home", "project.tests", "admin.users"), views.get(0).menuKeys());
        assertEquals(List.of("top.home"), views.get(1).menuKeys());
    }

    @Test
    void 按编码取角色大小写不敏感且缺失即报错() {
        RoleEntity qa = role("QA_LEAD", false, RoleEntity.CODE_DEVELOPER);
        when(roleRepo.findById("QA_LEAD")).thenReturn(Optional.of(qa));

        assertSame(qa, service.require(" qa_lead "));
        assertThrows(DevMindException.class, () -> service.require("missing"));
        assertThrows(DevMindException.class, () -> service.require(" "));
    }
}
