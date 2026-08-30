package com.devmind.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 前端静态托管 + SPA 路由回退。
 * 前后端一体：前端构建产物在 classpath:/static/，由后端托管；
 * 浏览器直接访问 /sessions 等前端路由时回退到 index.html（由 React Router 接管）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 注册 "/**" 后 Spring Boot 跳过默认 handler（hasMappingForPattern 检查）
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发期：前端 Vite dev server（5173）直连后端；生产同源无需 CORS
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("*")
                .allowedHeaders("*");
    }

    /**
     * SPA 回退解析器：静态资源不存在时返回 index.html；
     * api/ 与 ws/ 前缀保持原样（未命中则 404，不让 REST 假装成前端页面）。
     */
    static class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.isEmpty()) {
                return index(location);              // "/" → index.html
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")) {
                return null;                          // 保持 REST/WS 的 404 语义
            }
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;                     // 真实静态资源（js/css/图片）
            }
            return index(location);                   // 前端路由 → index.html
        }

        private Resource index(Resource location) throws IOException {
            Resource idx = location.createRelative("index.html");
            return idx.exists() && idx.isReadable() ? idx : null;
        }
    }
}
