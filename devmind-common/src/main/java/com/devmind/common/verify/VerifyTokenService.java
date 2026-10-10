package com.devmind.common.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * CAP-73 FR-04 verify 作用域 token：HMAC 签名、绑 sessionId + requirementId + 过期时间，
 * 仅授权「为该需求触发验证」一个动作（与节点 token 的拉包/上传能力分离）。
 *
 * <p>签发方 = 会话上下文装配（CAP-34 物化 devmind-verify 助手脚本时内嵌）；校验方 =
 * flow 的 {@code POST /api/verify/trigger}。密钥由注册方（flow 的 VerifyBeanConfig）解析：
 * 显式 {@code devmind.verify.token-secret}，空则生成随机密钥持久化 data/verify.key
 * （仿 JwtCodec 的 auth.key 先例，重启不失效）。</p>
 *
 * <p>token 形态：{@code dmv_<base64url(sid.rid.expEpochSec)>.<hex hmac>}。
 * 「会话结束即失效」由触发端叠加会话存活检查实现（token 自身只带过期兜底）。</p>
 *
 * <p>纯算法类无 Spring 注解：由消费模块以 @Bean 注册。</p>
 */
public class VerifyTokenService {

    public static final String PREFIX = "dmv_";

    private final byte[] key;

    public VerifyTokenService(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("verify token 密钥为空（devmind.verify.token-secret 解析失败）");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record Payload(String sessionId, String requirementId, Instant expiresAt) {
    }

    /** 签发：绑会话 + 需求 + 过期时间 */
    public String mint(String sessionId, String requirementId, Instant expiresAt) {
        String payload = sessionId + "." + requirementId + "." + expiresAt.getEpochSecond();
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return PREFIX + body + "." + hmacHex(body);
    }

    /** 校验签名与过期；任何不合法都返回 empty（调用方统一 401 文案） */
    public Optional<Payload> verify(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = token.substring(PREFIX.length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = rest.substring(0, dot);
        String sig = rest.substring(dot + 1);
        // 常量时间比较防时序侧信道（与 JWT 校验同姿势）
        if (!constantTimeEquals(hmacHex(body), sig)) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(exp);
        if (Instant.now().isAfter(expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(new Payload(parts[0], parts[1], expiresAt));
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
