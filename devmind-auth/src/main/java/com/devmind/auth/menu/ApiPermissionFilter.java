package com.devmind.auth.menu;

import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * CAP-103 操作权限过滤器：JWT 认证之后校验 {@link ApiPermissionCatalog} 登记的写接口。
 * 未命中规则 / 未认证交由既有链处理；ADMIN 短路；命中规则要求角色持有任一权限编码，否则 403。
 */
@Component
public class ApiPermissionFilter extends OncePerRequestFilter {

    private final MenuPermissionService menuPermissionService;
    private final ObjectMapper mapper;

    public ApiPermissionFilter(MenuPermissionService menuPermissionService, ObjectMapper mapper) {
        this.menuPermissionService = menuPermissionService;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiPermissionCatalog.Rule> rule =
                ApiPermissionCatalog.match(request.getMethod(), request.getRequestURI());
        if (rule.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || !(auth.getPrincipal() instanceof DevMindPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        if (RoleEntity.CODE_ADMIN.equals(principal.role())
                || menuPermissionService.hasAnyGrant(principal.roleCode(), rule.get().permissions())) {
            chain.doFilter(request, response);
            return;
        }
        writeForbidden(response, request.getRequestURI());
    }

    private void writeForbidden(HttpServletResponse res, String path) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(ApiError.of(
                ErrorCode.FORBIDDEN, "当前角色无权限执行该操作", path)));
    }
}
