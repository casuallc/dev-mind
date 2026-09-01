package com.devmind.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * CAP-01 认证基础设施：PasswordEncoder（BCrypt）。
 * 独立成类避免 SecurityConfig 与 IdentityService 种子之间的依赖环；
 * AuthProperties 由 app 主类的 @ConfigurationPropertiesScan 注册。
 */
@Configuration
public class AuthBeanConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
