package com.devmind.integration.connector.jira;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JiraConnector.describeError 单测：errorMessages 与 errors **合并**（创建 issue 时两者常同时出现，
 * 只取其一会把逐字段明细丢掉）、errors 逐条展开为「字段: 原因」、非错误封套返回 null
 * （交 extractMessage 退原始报文）。
 *
 * <p>回归：原实现把 errors 直接 toString()，Jira 一次回的 8~9 条必填/取值错误挤成 JSON 一行，
 * 用户既看不出哪几个字段必填，也读不到「用户 '刘长青' 不存在」这类取值错。
 */
class JiraConnectorErrorTextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode body(String json) {
        return mapper.readTree(json);
    }

    @Test
    void errors逐字段展开并与errorMessages合并() {
        String text = JiraConnector.describeError(body("""
                {"errorMessages":["工作流校验失败"],
                 "errors":{"components":"模块是必需的。","customfield_10207":"缺陷类型是必需的。",
                           "assignee":"用户 '刘长青' 不存在。"}}"""));

        assertEquals("工作流校验失败；components: 模块是必需的。；customfield_10207: 缺陷类型是必需的。"
                + "；assignee: 用户 '刘长青' 不存在。", text);
    }

    @Test
    void 只有errors时逐条展开不dumpJSON() {
        String text = JiraConnector.describeError(body("{\"errors\":{\"duedate\":\"到期日是必需的。\"}}"));

        assertEquals("duedate: 到期日是必需的。", text);
        assertFalse(text.contains("{"), text);
    }

    @Test
    void 只有errorMessages时保持既有文案() {
        assertEquals("Issue does not exist: PROJ-9",
                JiraConnector.describeError(body("{\"errorMessages\":[\"Issue does not exist: PROJ-9\"]}")));
    }

    @Test
    void 不是错误封套时返回null由调用方退原始报文() {
        assertNull(JiraConnector.describeError(body("{\"errorMessages\":[],\"errors\":{}}")));
        assertNull(JiraConnector.describeError(body("{\"message\":\"not found\"}")));
        assertNull(JiraConnector.describeError(body("null")));
        assertNull(JiraConnector.describeError(body("\"纯文本错误体\"")));
        assertNull(JiraConnector.describeError(null));
    }
}
