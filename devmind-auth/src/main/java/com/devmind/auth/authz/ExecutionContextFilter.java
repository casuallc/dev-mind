package com.devmind.auth.authz;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ExecutionContextHolder;
import com.devmind.common.authz.ExecutionSource;
import com.devmind.common.authz.SubjectType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * CAP-108 FR-02：HTTP 入口的可信执行上下文装配（认证之后运行，与 ApiPermissionFilter 同链）。
 * 上下文只能由认证适配器构造；未认证请求不建立上下文，受保护操作按缺身份拒绝（不回退 local）。
 * /open-api/** 的历史 key 主体标记为 SERVICE/OPEN_API（M1 兼容语义，M3 换显式集成主体）。
 */
@Component
public class ExecutionContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DevMindPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        boolean openApi = request.getRequestURI().startsWith("/open-api/");
        boolean legacyKey = principal.username() != null
                && principal.username().startsWith(SubjectResolver.LEGACY_KEY_PREFIX);
        SubjectType type = legacyKey ? SubjectType.SERVICE : SubjectType.USER;
        ExecutionSource source = openApi ? ExecutionSource.OPEN_API : ExecutionSource.WEB;
        ExecutionContext ctx = ExecutionContext.builder(type, principal.username(), source)
                .subjectId(principal.username())
                .traceId(UUID.randomUUID().toString().substring(0, 8))
                .build();
        try (ExecutionContextHolder.Scope ignored = ExecutionContextHolder.set(ctx)) {
            chain.doFilter(request, response);
        }
    }
}
