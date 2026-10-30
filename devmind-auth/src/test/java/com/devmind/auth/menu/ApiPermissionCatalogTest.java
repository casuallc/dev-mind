package com.devmind.auth.menu;

import com.devmind.auth.model.MenuItemEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPermissionCatalogTest {

    @Test
    void 规则只引用目录中的ACTION节点() {
        Set<String> actionKeys = MenuCatalog.items().stream()
                .filter(i -> MenuItemEntity.TYPE_ACTION.equals(i.nodeType()))
                .map(MenuCatalog.Item::menuKey)
                .collect(Collectors.toSet());
        for (ApiPermissionCatalog.Rule rule : ApiPermissionCatalog.rules()) {
            assertFalse(rule.permissions().isEmpty(), "规则缺少权限编码: " + rule.pattern());
            for (String key : rule.permissions()) {
                assertTrue(actionKeys.contains(key), "规则引用了非 ACTION 节点: " + key);
            }
        }
    }

    @Test
    void 按方法加Ant路径首条命中() {
        assertEquals(Set.of("project.builds.action.trigger"),
                match("POST", "/api/projects/7/builds"));
        assertEquals(Set.of("admin.projects.action.create-project"),
                match("POST", "/api/projects"));
        assertEquals(Set.of("admin.projects.action.edit-project"),
                match("PUT", "/api/projects/7"));
        assertTrue(ApiPermissionCatalog.match("GET", "/api/projects/7/builds").isEmpty());
        assertTrue(ApiPermissionCatalog.match("POST", "/api/not-registered").isEmpty());
        assertTrue(ApiPermissionCatalog.match(null, "/api/projects").isEmpty());
        assertTrue(ApiPermissionCatalog.match("POST", null).isEmpty());
    }

    @Test
    void 共享接口登记OR权限集合() {
        assertEquals(Set.of("project.sessions.action.create", "top.worklog.action.create-session"),
                match("POST", "/api/sessions"));
        assertEquals(Set.of("project.sessions.action.manage", "top.worklog.action.manage-session"),
                match("POST", "/api/sessions/9/finish"));
        assertEquals(Set.of("admin.projects.action.sediment-proposal", "project.sessions.action.sediment"),
                match("POST", "/api/knowledge/proposals"));
    }

    @Test
    void 未登记规则的共享接口不拦截() {
        assertTrue(ApiPermissionCatalog.match("POST", "/api/attachments").isEmpty());
    }

    private static Set<String> match(String method, String path) {
        return ApiPermissionCatalog.match(method, path).orElseThrow().permissions();
    }
}
