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
 *
 * <p>CAP-32：header 缺失时回退读 GET 请求的 {@code ?access_token=} 参数——
 * {@code <img>}/markdown 渲染/新窗口打开附件 raw URL 无法带 header，query token 是唯一通用解
 * （runner 包下载已有 ?token= 先例）。仅限 GET，写操作不接受 query token（防 URL 泄漏被误用）。</p>
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
        String token = resolveToken(request);
        if (token != null) {
            jwtCodec.verify(token).ifPresent(claims -> {
                DevMindPrincipal principal = new DevMindPrincipal(claims.subject(), claims.role());
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + claims.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    /** header 优先；缺失时仅 GET 回退 ?access_token=。 */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if ("GET".equals(request.getMethod())) {
            String param = request.getParameter("access_token");
            if (param != null && !param.isBlank()) {
                return param;
            }
        }
        return null;
    }
}
