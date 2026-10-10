package com.devmind.flow.verify;

import com.devmind.common.verify.VerifyTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * CAP-73 verify 设施装配：token 服务密钥解析——显式配置优先，空则生成随机密钥持久化
 * data/verify.key（仿 JwtCodec 的 auth.key 先例：本机专属、勿提交、重启不失效）。
 */
@Configuration
public class VerifyBeanConfig {

    private static final Logger log = LoggerFactory.getLogger(VerifyBeanConfig.class);

    @Bean
    public VerifyTokenService verifyTokenService(VerifyProperties props) {
        String secret = props.getTokenSecret();
        if (secret == null || secret.isBlank()) {
            secret = loadOrGenerateKey();
        }
        return new VerifyTokenService(secret);
    }

    private String loadOrGenerateKey() {
        Path file = Path.of("data", "verify.key");
        try {
            if (Files.exists(file)) {
                String s = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (s.length() >= 16) {
                    return s;
                }
            }
            byte[] gen = new byte[32];
            new SecureRandom().nextBytes(gen);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : gen) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            log.info("已自动生成 verify token 密钥: {}（本机专属，勿提交）", file.toAbsolutePath());
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("verify token 密钥初始化失败: " + e.getMessage(), e);
        }
    }
}
