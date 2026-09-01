package com.devmind.auth.security;

/**
 * 认证主体：JWT 校验通过后放入 SecurityContext。
 * role 来自 token claim（角色/状态变更在下一次登录或刷新后生效）。
 */
public record DevMindPrincipal(String username, String role) {
}
