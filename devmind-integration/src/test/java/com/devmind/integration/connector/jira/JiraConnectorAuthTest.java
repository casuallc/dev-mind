package com.devmind.integration.connector.jira;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JiraConnector.authorizationHeader 单测：按 secret 存储格式自探测——
 * 含 '\n' 即 BASIC "username\npassword" → Basic base64(user:pass)（密码允许含冒号）；
 * 否则按 PAT → Bearer。与实例 authType 无关（CAP-35 个人账号认证方式独立于实例）。
 */
class JiraConnectorAuthTest {

    @Test
    void PAT走Bearer头() {
        assertEquals("Bearer pat-abc-123", JiraConnector.authorizationHeader("pat-abc-123"));
    }

    @Test
    void BASIC按换行拆用户名密码转Base64() {
        String header = JiraConnector.authorizationHeader("zhangsan\np@ss");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("zhangsan:p@ss".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
    }

    @Test
    void BASIC密码含冒号不受影响() {
        String header = JiraConnector.authorizationHeader("u1\na:b:c");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("u1:a:b:c".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
    }

    @Test
    void 实例BASIC但个人凭据为PAT时按PAT组头() {
        // 回归：旧实现按实例 authType 组头，个人 PAT 会被错误包成 Basic base64(pat)
        assertEquals("Bearer personal-pat", JiraConnector.authorizationHeader("personal-pat"));
    }

    @Test
    void 实例PAT但个人凭据为BASIC时按BASIC组头() {
        // 回归：旧实现会组出 "Bearer username\npassword"（非法头 + 认证必然失败）
        String header = JiraConnector.authorizationHeader("lisi\nsecret");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("lisi:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
    }
}
