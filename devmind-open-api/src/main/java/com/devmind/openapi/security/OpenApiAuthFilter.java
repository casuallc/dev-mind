package com.devmind.openapi.security;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.auth.security.PreJwtAuthFilter;
import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.ErrorCode;
import com.devmind.openapi.model.ApiKeyEntity;
import com.devmind.openapi.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CAP-20 open-api 独立认证过滤器：仅拦 /open-api/**，HMAC-SHA256 签名（规范见 {@link HmacSigner}）。
 * 请求头：X-Access-Key / X-Timestamp（epoch 秒，±5min 防重放） / X-Signature。
 * 校验通过 → 放入等价 ADMIN 的 Authentication（principal 形如 apikey:<名称>，写各表 created_by 可追溯）；
 * 失败 → 直接 401 {@link ApiError}，不放行到授权层。
 */
@Component
public class OpenApiAuthFilter extends PreJwtAuthFilter {

    public static final String HEADER_ACCESS_KEY = "X-Access-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Signature";

    /** 防重放时间窗：±5 分钟 */
    private static final long MAX_SKEW_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthFilter.class);

    private final ApiKeyService keyService;
    private final ObjectMapper mapper;

    public OpenApiAuthFilter(ApiKeyService keyService, ObjectMapper mapper) {
        this.keyService = keyService;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/open-api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        String accessKey = wrapped.getHeader(HEADER_ACCESS_KEY);
        String timestamp = wrapped.getHeader(HEADER_TIMESTAMP);
        String signature = wrapped.getHeader(HEADER_SIGNATURE);

        if (isBlank(accessKey) || isBlank(timestamp) || isBlank(signature)) {
            reject(response, request, "缺少签名头（X-Access-Key / X-Timestamp / X-Signature）");
            return;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            reject(response, request, "X-Timestamp 非法（应为 epoch 秒）");
            return;
        }
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > MAX_SKEW_SECONDS) {
            reject(response, request, "X-Timestamp 超出 ±5 分钟窗口");
            return;
        }
        Optional<ApiKeyEntity> found = keyService.findVerifiable(accessKey);
        if (found.isEmpty()) {
            reject(response, request, "AccessKey 不存在/已禁用/已过期");
            return;
        }
        ApiKeyEntity key = found.get();
        String pathWithQuery = wrapped.getRequestURI()
                + (wrapped.getQueryString() == null ? "" : "?" + wrapped.getQueryString());
        String expected = HmacSigner.hmacSha256Hex(key.getSecretHash(),
                HmacSigner.stringToSign(wrapped.getMethod(), pathWithQuery, timestamp.trim(), wrapped.getBody()));
        if (!HmacSigner.constantTimeEquals(expected, signature.trim().toLowerCase())) {
            reject(response, request, "签名校验失败");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal("apikey:" + key.getName(), "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        keyService.touchLastUsed(key.getId());
        log.debug("open-api 认证通过: {} {} (key={})", request.getMethod(), pathWithQuery, key.getName());
        chain.doFilter(wrapped, response);
    }

    private void reject(HttpServletResponse res, HttpServletRequest req, String message) throws IOException {
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(
                ApiError.of(ErrorCode.UNAUTHORIZED, message, req.getRequestURI())));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
