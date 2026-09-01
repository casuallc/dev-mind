package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CAP-01 JWT 编解码：HS256（HmacSHA256 + Base64Url），纯 JDK + Jackson，不引第三方 JWT 库
 * （规避公司 Nexus 缺包风险）。payload 扁平四字段：sub（用户名）/ role / iat / exp（epoch 秒）。
 * 密钥：devmind.auth.jwt-secret；空则生成随机密钥持久化 data/auth.key（仿 CredentialCrypto）。
 */
@Component
public class JwtCodec {

    public record JwtClaims(String subject, String role, long expiresAt) {}

    private static final Logger log = LoggerFactory.getLogger(JwtCodec.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final byte[] HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8);

    private final AuthProperties props;
    private final ObjectMapper mapper;
    private byte[] key;

    public JwtCodec(AuthProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        this.key = loadKey();
    }

    /** 签发：sub + role + jti（随机，保证同秒多次签发不撞车）+ 绝对过期时刻。 */
    public String issue(String subject, String role, Instant expiresAt) {
        byte[] jti = new byte[8];
        new SecureRandom().nextBytes(jti);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subject);
        payload.put("role", role);
        payload.put("jti", HexFormat.of().formatHex(jti));
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        String h = B64.encodeToString(HEADER);
        String p = B64.encodeToString(mapper.writeValueAsBytes(payload));
        return h + "." + p + "." + B64.encodeToString(sign(h + "." + p));
    }

    /** 校验：格式/签名/过期任一不过 → empty（调用方按 401 处理）。 */
    public Optional<JwtClaims> verify(String token) {
        try {
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = mapper.readValue(B64D.decode(parts[1]), Map.class);
            Object sub = payload.get("sub");
            Object role = payload.get("role");
            Object exp = payload.get("exp");
            if (sub == null || exp == null) {
                return Optional.empty();
            }
            long expSec = ((Number) exp).longValue();
            if (Instant.ofEpochSecond(expSec).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new JwtClaims(sub.toString(), role == null ? null : role.toString(), expSec));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] loadKey() {
        String cfg = props.getJwtSecret();
        if (cfg != null && !cfg.isBlank()) {
            try {
                return B64D.decode(cfg.trim());
            } catch (IllegalArgumentException e) {
                return cfg.getBytes(StandardCharsets.UTF_8);
            }
        }
        Path file = Path.of("data", "auth.key");
        try {
            if (Files.exists(file)) {
                byte[] b = Files.readAllBytes(file);
                if (b.length >= 16) {
                    return b;
                }
            }
            byte[] gen = new byte[32];
            new SecureRandom().nextBytes(gen);
            Files.createDirectories(file.getParent());
            Files.write(file, gen);
            log.info("已自动生成 JWT 密钥: {}（本机专属，勿提交）", file.toAbsolutePath());
            return gen;
        } catch (Exception e) {
            byte[] gen = new byte[32];
            new SecureRandom().nextBytes(gen);
            log.warn("无法持久化 JWT 密钥，回退为随机密钥（重启后旧 token 全部失效）: {}", e.toString());
            return gen;
        }
    }
}
