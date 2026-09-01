package com.devmind.openapi.security;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiAuthFilterTest {

    private static final String SK = "sk_testsecret";
    private static final String SECRET_HASH = ApiKeyService.sha256Hex(SK);

    private final ApiKeyService keyService = mock(ApiKeyService.class);
    private final OpenApiAuthFilter filter = new OpenApiAuthFilter(keyService, new ObjectMapper());

    @BeforeEach
    void setUp() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.setId(1L);
        key.setAccessKey("ak_test");
        key.setSecretHash(SECRET_HASH);
        key.setName("ci-bot");
        key.setEnabled(true);
        when(keyService.findVerifiable("ak_test")).thenReturn(Optional.of(key));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 合法签名放行并设置等价ADMIN身份() throws Exception {
        byte[] body = "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String path = "/open-api/v1/projects";
        String sig = sign("POST", path, ts, body);

        MockHttpServletRequest req = request("POST", path, ts, sig, body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest(), "下游应收到请求");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        DevMindPrincipal p = assertInstanceOf(DevMindPrincipal.class, auth.getPrincipal());
        assertEquals("apikey:ci-bot", p.username());
        assertEquals("ADMIN", p.role());
    }

    @Test
    void query串参与签名() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String path = "/open-api/v1/projects?tag=a";
        String sig = sign("GET", path, ts, null);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open-api/v1/projects");
        req.setQueryString("tag=a");
        req.addHeader(OpenApiAuthFilter.HEADER_ACCESS_KEY, "ak_test");
        req.addHeader(OpenApiAuthFilter.HEADER_TIMESTAMP, ts);
        req.addHeader(OpenApiAuthFilter.HEADER_SIGNATURE, sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void 错误密钥签名401() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String path = "/open-api/v1/projects";
        String badSig = HmacSigner.hmacSha256Hex(ApiKeyService.sha256Hex("sk_wrong"),
                HmacSigner.stringToSign("GET", path, ts, new byte[0]));

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("GET", path, ts, badSig, null), res, new MockFilterChain());

        assertEquals(401, res.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void 过期时间戳401() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond() - 400);
        String path = "/open-api/v1/projects";
        String sig = sign("GET", path, ts, null);

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("GET", path, ts, sig, null), res, new MockFilterChain());

        assertEquals(401, res.getStatus());
    }

    @Test
    void 篡改body后签名不匹配401() throws Exception {
        byte[] signed = "{\"name\":\"a\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"name\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String path = "/open-api/v1/projects";
        String sig = sign("POST", path, ts, signed);

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(request("POST", path, ts, sig, tampered), res, new MockFilterChain());

        assertEquals(401, res.getStatus());
    }

    @Test
    void 缺少签名头401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open-api/v1/projects");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
    }

    @Test
    void 非开放面路径不拦截() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertNotNull(chain.getRequest());
    }

    private String sign(String method, String path, String ts, byte[] body) {
        return HmacSigner.hmacSha256Hex(SECRET_HASH, HmacSigner.stringToSign(method, path, ts, body));
    }

    private MockHttpServletRequest request(String method, String path, String ts, String sig, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader(OpenApiAuthFilter.HEADER_ACCESS_KEY, "ak_test");
        req.addHeader(OpenApiAuthFilter.HEADER_TIMESTAMP, ts);
        req.addHeader(OpenApiAuthFilter.HEADER_SIGNATURE, sig);
        if (body != null) {
            req.setContent(body);
        }
        return req;
    }
}
