package com.devmind.auth.security;

import com.devmind.auth.JwtCodec;
import com.devmind.auth.config.AuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CAP-32：{@link JwtAuthFilter} 的 GET {@code ?access_token=} 回退——
 * img/markdown/新窗口无法带 Authorization header，仅 GET 接受 query token。
 */
class JwtAuthFilterTest {

    private JwtCodec codec() throws Exception {
        AuthProperties props = new AuthProperties();
        props.setJwtSecret("test-secret-key-for-jwt-filter");
        JwtCodec c = new JwtCodec(props, new ObjectMapper());
        Method init = JwtCodec.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(c);
        return c;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void run(JwtAuthFilter filter, MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : ((DevMindPrincipal) auth.getPrincipal()).username();
    }

    @Test
    void headerBearer仍然优先() throws Exception {
        JwtCodec c = codec();
        String token = c.issue("alice", "ADMIN", Instant.now().plusSeconds(3600));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/attachments/x/raw");
        req.addHeader("Authorization", "Bearer " + token);
        run(new JwtAuthFilter(c), req);
        assertEquals("alice", currentUser());
    }

    @Test
    void get请求支持accessToken参数回退() throws Exception {
        JwtCodec c = codec();
        String token = c.issue("bob", "DEVELOPER", Instant.now().plusSeconds(3600));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/attachments/x/raw");
        req.setParameter("access_token", token);
        run(new JwtAuthFilter(c), req);
        assertEquals("bob", currentUser());
    }

    @Test
    void 非get请求不接受queryToken() throws Exception {
        JwtCodec c = codec();
        String token = c.issue("bob", "DEVELOPER", Instant.now().plusSeconds(3600));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/attachments");
        req.setParameter("access_token", token);
        run(new JwtAuthFilter(c), req);
        assertNull(currentUser());
    }

    @Test
    void 非法queryToken不填充上下文() throws Exception {
        JwtCodec c = codec();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/attachments/x/raw");
        req.setParameter("access_token", "not.a.token");
        run(new JwtAuthFilter(c), req);
        assertNull(currentUser());
    }
}
