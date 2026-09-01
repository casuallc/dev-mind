package com.devmind.auth.security;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 需要插在 {@link JwtAuthFilter} 之前执行的认证过滤器基类（SPI）。
 * 其他模块（如 CAP-20 open-api 的 HMAC 认证）继承本类并声明为 @Component，
 * SecurityConfig 通过 ObjectProvider 自动收集注册——auth 模块无需反向依赖业务模块。
 */
public abstract class PreJwtAuthFilter extends OncePerRequestFilter {
}
