package com.devmind.common.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS / WebSocket 握手来源白名单（devmind.cors.allowed-origins），全平台统一口径。
 * 默认放行本地开发来源；联调或分离部署时在 application-local.yml 追加实际来源，如：
 *   devmind.cors.allowed-origins: http://localhost:5173,http://192.168.x.x:5173
 * 消费方：auth SecurityConfig（CORS 过滤器）、app WebConfig（MVC CORS）、各模块 WS 握手。
 * 由 devmind-app 的 @ConfigurationPropertiesScan 装配。
 */
@ConfigurationProperties(prefix = "devmind.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of(
            "http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost:8080", "http://127.0.0.1:8080");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /** 供 setAllowedOrigins(String...) 等变参接口使用 */
    public String[] originsArray() {
        return allowedOrigins.toArray(String[]::new);
    }
}
