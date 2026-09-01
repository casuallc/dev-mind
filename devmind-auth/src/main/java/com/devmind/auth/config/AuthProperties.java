package com.devmind.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * CAP-01 认证配置。jwt-secret 留空时自动生成并持久化到 data/auth.key（重启不失效）；
 * admin-password 仅在首次种子 admin 时读取。
 */
@ConfigurationProperties(prefix = "devmind.auth")
public class AuthProperties {

    /** JWT HS256 密钥（base64 或原始串）；空 = 自动生成持久化到 data/auth.key */
    private String jwtSecret = "";

    /** 首次种子 admin 的初始密码 */
    private String adminPassword = "admin123";

    /** access token 有效期 */
    private Duration accessTtl = Duration.ofHours(2);

    /** refresh token 有效期 */
    private Duration refreshTtl = Duration.ofDays(14);

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public Duration getAccessTtl() { return accessTtl; }
    public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
    public Duration getRefreshTtl() { return refreshTtl; }
    public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
}
