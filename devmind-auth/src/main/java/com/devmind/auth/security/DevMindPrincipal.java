package com.devmind.auth.security;

/**
 * 认证主体：JWT 校验通过后放入 SecurityContext。
 * role 为角色对应的基础权限（ADMIN/DEVELOPER/VIEWER，过滤器链使用）；
 * roleCode 为可管理角色编码（菜单权限判定使用）。均来自 token claim，变更在下一次登录或刷新后生效。
 */
public record DevMindPrincipal(String username, String role, String roleCode) {

    /** 兼容系统内部构造（API Key/异步任务/测试探针）：角色编码默认同基础权限。 */
    public DevMindPrincipal(String username, String role) {
        this(username, role, role);
    }
}
