package com.devmind.auth.menu;

import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.security.DevMindPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiPermissionFilterTest {

    private final MenuPermissionService service = mock(MenuPermissionService.class);
    private final ApiPermissionFilter filter =
            new ApiPermissionFilter(service, JsonMapper.builder().build());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String baseRole, String roleCode) {
        var auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal("tester", baseRole, roleCode), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private MockHttpServletResponse run(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void 未登记规则的请求直接放行() throws Exception {
        authenticate(RoleEntity.CODE_VIEWER, RoleEntity.CODE_VIEWER);
        assertEquals(200, run("POST", "/api/attachments").getStatus());
        assertEquals(200, run("GET", "/api/projects/1/builds").getStatus());
    }

    @Test
    void 命中规则但未认证交由既有链处理() throws Exception {
        assertEquals(200, run("POST", "/api/projects").getStatus());
    }

    @Test
    void ADMIN短路放行已登记写接口() throws Exception {
        authenticate(RoleEntity.CODE_ADMIN, RoleEntity.CODE_ADMIN);
        assertEquals(200, run("POST", "/api/projects").getStatus());
    }

    @Test
    void 持有任一权限编码即放行() throws Exception {
        authenticate(RoleEntity.CODE_DEVELOPER, "QA_LEAD");
        when(service.hasAnyGrant(any(), anyCollection())).thenReturn(true);
        assertEquals(200, run("POST", "/api/knowledge/proposals").getStatus());
    }

    @Test
    void 无授权返回403并携带统一错误体() throws Exception {
        authenticate(RoleEntity.CODE_DEVELOPER, "VIEWER_ONLY");
        when(service.hasAnyGrant(any(), anyCollection())).thenReturn(false);
        MockHttpServletResponse response = run("POST", "/api/projects");

        assertEquals(403, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("当前角色无权限执行该操作"), body);
        assertTrue(body.contains("/api/projects"), body);
    }

    @Test
    void OR集合原样传给授权判定() throws Exception {
        authenticate(RoleEntity.CODE_DEVELOPER, "QA_LEAD");
        when(service.hasAnyGrant(any(), anyCollection())).thenReturn(true);
        run("POST", "/api/sessions");
        org.mockito.Mockito.verify(service).hasAnyGrant(
                org.mockito.ArgumentMatchers.eq("QA_LEAD"),
                org.mockito.ArgumentMatchers.<Collection<String>>argThat(keys ->
                        keys.size() == 2 && keys.contains("project.sessions.action.create")
                                && keys.contains("top.worklog.action.create-session")));
    }
}
