package com.devmind.auth.security;

import com.devmind.auth.JwtCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * CAP-01 JWT 认证过滤器：解析 {@code Authorization: Bearer <token>}，
 * 校验签名/过期后把 {@link DevMindPrincipal} 放入 SecurityContext。
 * 非法/缺失 token 不直接拒绝——由授权规则 + authenticationEntryPoint 统一出 401。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtCodec jwtCodec;

    public JwtAuthFilter(JwtCodec jwtCodec) {
        this.jwtCodec = jwtCodec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtCodec.verify(header.substring(7)).ifPresent(claims -> {
                DevMindPrincipal principal = new DevMindPrincipal(claims.subject(), claims.role());
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
