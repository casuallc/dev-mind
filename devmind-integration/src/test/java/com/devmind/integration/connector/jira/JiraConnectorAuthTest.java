package com.devmind.integration.connector.jira;

import com.devmind.integration.model.IntegrationEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JiraConnector.authorizationHeader 单测：PAT → Bearer；BASIC → Basic base64(user:pass)，
 * secret 存储格式 "username\npassword"（密码本身允许含冒号）。
 */
class JiraConnectorAuthTest {

    @Test
    void PAT走Bearer头() {
        assertEquals("Bearer pat-abc-123",
                JiraConnector.authorizationHeader(IntegrationEntity.AUTH_PAT, "pat-abc-123"));
    }

    @Test
    void BASIC按换行拆用户名密码转Base64() {
        String header = JiraConnector.authorizationHeader(IntegrationEntity.AUTH_BASIC, "zhangsan\np@ss");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("zhangsan:p@ss".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
    }

    @Test
    void BASIC密码含冒号不受影响() {
        String header = JiraConnector.authorizationHeader(IntegrationEntity.AUTH_BASIC, "u1\na:b:c");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("u1:a:b:c".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
    }

    @Test
    void 未知authType回退Bearer() {
        assertEquals("Bearer x", JiraConnector.authorizationHeader(null, "x"));
        assertEquals("Bearer x", JiraConnector.authorizationHeader("OAUTH", "x"));
    }
}
