package com.devmind.session.controller;

import com.devmind.common.exception.ApiError;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * devmind.error.include-stacktrace 开关行为：开=true 响应体带堆栈，关=false 为 null（序列化省略）。
 * 不起 Spring 容器：手工构造 Handler + 反射注入开关 + JDK 动态代理伪造 request。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler newHandler(boolean includeStackTrace) throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Field f = GlobalExceptionHandler.class.getDeclaredField("includeStackTrace");
        f.setAccessible(true);
        f.setBoolean(handler, includeStackTrace);
        return handler;
    }

    private HttpServletRequest fakeRequest() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI" -> "/api/demo";
                    case "getMethod" -> "GET";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void handleOther_includesStackTrace_whenEnabled() throws Exception {
        ResponseEntity<ApiError> res = newHandler(true).handleOther(new IllegalStateException("boom"), fakeRequest());
        assertEquals(500, res.getStatusCode().value());
        ApiError body = res.getBody();
        assertNotNull(body);
        assertNotNull(body.stackTrace());
        assertTrue(body.stackTrace().contains("IllegalStateException"));
        assertTrue(body.stackTrace().contains("boom"));
    }

    @Test
    void handleOther_omitsStackTrace_whenDisabled() throws Exception {
        ResponseEntity<ApiError> res = newHandler(false).handleOther(new IllegalStateException("boom"), fakeRequest());
        ApiError body = res.getBody();
        assertNotNull(body);
        assertNull(body.stackTrace());
        assertEquals(ErrorCode.INTERNAL.getCode(), body.code());
    }

    @Test
    void handleBiz_includesStackTrace_whenEnabled() throws Exception {
        ResponseEntity<ApiError> res = newHandler(true)
                .handleBiz(new DevMindException(ErrorCode.CONFLICT, "状态冲突演示"), fakeRequest());
        assertEquals(409, res.getStatusCode().value());
        ApiError body = res.getBody();
        assertNotNull(body);
        assertNotNull(body.stackTrace());
        assertTrue(body.stackTrace().contains("DevMindException"));
    }

    @Test
    void handleBiz_omitsStackTrace_whenDisabled() throws Exception {
        ResponseEntity<ApiError> res = newHandler(false)
                .handleBiz(new DevMindException(ErrorCode.CONFLICT, "状态冲突演示"), fakeRequest());
        ApiError body = res.getBody();
        assertNotNull(body);
        assertNull(body.stackTrace());
    }
}
