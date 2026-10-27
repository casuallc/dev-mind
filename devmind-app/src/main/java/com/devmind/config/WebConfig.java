package com.devmind.config;

import java.io.IOException;

import com.devmind.common.security.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 前端静态托管 + SPA 路由回退。
 * 前端资源只加载外置 file:./ui/（相对启动工作目录，分发包包根；jar 内不含静态资源）；
 * 浏览器直接访问 /sessions 等前端路由时回退到 index.html（由 React Router 接管）。
 * 开发态前端走 Vite dev server（:5173 代理 /api、/ws），后端不托管静态。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    public WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 注册 "/**" 后 Spring Boot 跳过默认 handler（hasMappingForPattern 检查）
        registry.addResourceHandler("/**")
                .addResourceLocations("file:./ui/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // ResourceHttpRequestHandler 对空路径短路返回 null（不经 resolver 链），
        // SPA 回退解析器管不到 "/"，故由视图控制器显式 forward 到 index.html
        // （URL 保持 "/"，React Router 接管后再跳 /overview）
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * index.html / SPA 路由强制回源校验（Cache-Control: no-cache）。
     * 资源处理器只发 Last-Modified、无 Cache-Control → 浏览器按 10% 启发式缓存
     * index.html，部署新前端后仍引用旧 hash 包——「改了线上不生效」（2026-10-27
     * OKR 弹窗修复后用户端仍复现旧行为，根因即此）。no-cache 不禁缓存只强制校验，
     * 未变仍 304 省流量；带内容 hash 的 /assets/ 与 /api/、/ws/ 前缀不动。
     */
    @Bean
    OncePerRequestFilter spaIndexNoCacheFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                String p = req.getRequestURI();
                if ("GET".equals(req.getMethod())
                        && !p.startsWith("/api/") && !p.startsWith("/ws/")
                        && !p.startsWith("/assets/") && !p.startsWith("/h2-console")) {
                    res.setHeader("Cache-Control", "no-cache");
                }
                chain.doFilter(req, res);
            }
        };
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发期：前端 Vite dev server（5173）直连后端；生产同源无需 CORS
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.originsArray())
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
