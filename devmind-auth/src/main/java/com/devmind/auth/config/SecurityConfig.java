package com.devmind.auth.config;

import com.devmind.auth.security.JwtAuthFilter;
import com.devmind.auth.security.PreJwtAuthFilter;
import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.security.CorsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CAP-01 FR-04 过滤器链：无状态 JWT。粗粒度角色规则——
 * /api/** 读方法三角色均可、写方法 ADMIN/DEVELOPER、用户管理仅 ADMIN；
 * 登录/刷新/登出、健康检查、H2 控制台、WebSocket（MVP）放行。
 * 401/403 统一输出 {@link ApiError} 结构。
 */
@Configuration
public class SecurityConfig {

    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public SecurityConfig(ObjectMapper mapper, CorsProperties corsProperties) {
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                                   ObjectProvider<PreJwtAuthFilter> preJwtFilters) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())) // H2 控制台 iframe
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/api/health", "/h2-console/**", "/ws/**").permitAll()
                        .requestMatchers("/api/auth/users", "/api/auth/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/change-password").authenticated()
                        // CAP-20：API Key 管理仅 ADMIN（开放面 /open-api/** 由 OpenApiAuthFilter 独立认证，不走 JWT）
                        .requestMatchers("/api/open-keys", "/api/open-keys/**").hasRole("ADMIN")
                        // CAP-21：Agent 节点管理（含注册 token 签发）写操作仅 ADMIN；/ws/agent 由节点 token 自证
                        // FR-09 runner 包下载：permitAll 放行，节点 token 或登录态由控制器内双重判定
                        .requestMatchers(HttpMethod.GET, "/api/agent-nodes/runner-package/download").permitAll()
                        // CAP-34 FR-03：上下文包拉取 permitAll 放行，节点 token 由控制器内判定
                        .requestMatchers(HttpMethod.GET, "/api/agent/context/**").permitAll()
                        // CAP-37 FR-01：会话产出上传 permitAll 放行，节点 token 由控制器内判定
                        .requestMatchers(HttpMethod.POST, "/api/agent/output/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/agent-nodes", "/api/agent-nodes/**").hasRole("ADMIN")
                        // CAP-34 FR-07：编辑节点标签
                        .requestMatchers(HttpMethod.PUT, "/api/agent-nodes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent-nodes/**").hasRole("ADMIN")
                        // CAP-20：AI 智能接入（全自动 bypassPermissions 会话）仅 ADMIN 可发起
                        .requestMatchers(HttpMethod.POST, "/api/projects/onboard").hasRole("ADMIN")
                        // CAP-18：平台集成实例（含凭据）的写操作仅 ADMIN；读与项目级动作走通用规则
                        .requestMatchers(HttpMethod.POST, "/api/integrations", "/api/integrations/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/integrations/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/integrations/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        // CAP-29：全局仓库登记写操作仅 ADMIN（含 fetch/clone 触发）；GET 走上方全认证
                        .requestMatchers(HttpMethod.POST, "/api/repos", "/api/repos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/repos/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/repos/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "DEVELOPER")
                        .anyRequest().permitAll())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) ->
                                writeError(res, 401, ErrorCode.UNAUTHORIZED, "未登录或 token 已过期", req.getRequestURI()))
                        .accessDeniedHandler((req, res, ex) ->
                                writeError(res, 403, ErrorCode.FORBIDDEN, "当前角色无权限执行该操作", req.getRequestURI())))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        // CAP-20 等模块提供的 PreJwtAuthFilter（如 HMAC 签名认证）插在 JWT 过滤器之前
        preJwtFilters.orderedStream().forEach(f -> http.addFilterBefore(f, JwtAuthFilter.class));
        return http.build();
    }

    /** 与 WebConfig#addCorsMappings 同口径；Security 过滤器先于 MVC，预检（OPTIONS）必须在此放行 */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(corsProperties.getAllowedOrigins());
        cfg.setAllowedMethods(List.of("*"));
        cfg.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse res, int status,
                            ErrorCode code, String message, String path) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(ApiError.of(code, message, path)));
    }
}
