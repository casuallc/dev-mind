package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtCodecTest {

    private JwtCodec codec() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecret("test-secret-key-for-jwt-codec");
        JwtCodec c = new JwtCodec(props, new ObjectMapper());
        c.init();
        return c;
    }

    @Test
    void 签发后可解析出用户与角色() {
        JwtCodec c = codec();
        String token = c.issue("alice", "ADMIN", Instant.now().plusSeconds(3600));
        Optional<JwtCodec.JwtClaims> claims = c.verify(token);
        assertTrue(claims.isPresent());
        assertEquals("alice", claims.get().subject());
        assertEquals("ADMIN", claims.get().role());
    }

    @Test
    void 篡改签名或载荷被拒绝() {
        JwtCodec c = codec();
        String token = c.issue("alice", "ADMIN", Instant.now().plusSeconds(3600));
        String[] parts = token.split("\\.");
        // 篡改 payload（role 换成 VIEWER 的合法 base64 也行，签名对不上即可）
        String tampered = parts[0] + "." + parts[1] + "x" + "." + parts[2];
        assertTrue(c.verify(tampered).isEmpty());
        // 换一把钥匙签的 token 也拒绝
        AuthProperties other = new AuthProperties();
        other.setJwtSecret("another-secret");
        JwtCodec c2 = new JwtCodec(other, new ObjectMapper());
        c2.init();
        assertTrue(c.verify(c2.issue("alice", "ADMIN", Instant.now().plusSeconds(3600))).isEmpty());
    }

    @Test
    void 过期与非法格式被拒绝() {
        JwtCodec c = codec();
        String expired = c.issue("alice", "ADMIN", Instant.now().minusSeconds(10));
        assertTrue(c.verify(expired).isEmpty());
        assertTrue(c.verify("not-a-jwt").isEmpty());
        assertTrue(c.verify(null).isEmpty());
        assertTrue(c.verify("").isEmpty());
    }
}
