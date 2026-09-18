package com.devmind.integration.connector.jira;

import com.devmind.integration.connector.IntegrationConnector.CreateFieldRef;
import com.devmind.integration.connector.IntegrationConnector.IssuePage;
import com.devmind.integration.connector.IntegrationConnector.JiraIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraIssueMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/jira/" + name)) {
            return mapper.readTree(in);
        }
    }

    @Test
    void 分页元数据与issue列表完整映射() throws Exception {
        IssuePage page = JiraIssueMapper.toPage(fixture("search-page1.json"));
        assertEquals(0, page.startAt());
        assertEquals(2, page.maxResults());
        assertEquals(3, page.total());
        assertEquals(2, page.issues().size());

        JiraIssue bug = page.issues().get(0);
        assertEquals("PROJ-1", bug.key());
        assertEquals("登录页报错", bug.summary());
        assertTrue(bug.description().contains("复现步骤"));
        assertEquals("Bug", bug.issueType());
        assertEquals("High", bug.priority());
        assertEquals(2, bug.labels().size());
        assertEquals("Open", bug.status());
        assertEquals("张三", bug.reporter());
        assertEquals("李四", bug.assignee());
        assertEquals(LocalDate.parse("2026-09-30"), bug.dueDate());
        assertEquals(List.of("1.0", "1.1"), bug.fixVersions());
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"), bug.updated());
        // CAP-27：time tracking 秒数
        assertEquals(7200L, bug.originalEstimateSec());
        assertEquals(3600L, bug.timeSpentSec());
    }

    @Test
    void 空值字段安全降级() throws Exception {
        IssuePage page = JiraIssueMapper.toPage(fixture("search-page1.json"));
        JiraIssue story = page.issues().get(1);
        assertNull(story.description());
        assertNull(story.priority());
        assertNull(story.reporter());
        assertNull(story.assignee()); // 未指派返回 null
        assertNull(story.dueDate());
        assertTrue(story.fixVersions().isEmpty());
        assertTrue(story.labels().isEmpty());
        assertNull(story.originalEstimateSec()); // 未启用工时跟踪返回 null
        assertNull(story.timeSpentSec());
    }

    @Test
    void 缺key的脏数据被跳过() throws Exception {
        IssuePage page = JiraIssueMapper.toPage(fixture("search-page2-dirty.json"));
        assertEquals(1, page.issues().size());
        assertEquals("PROJ-3", page.issues().get(0).key());
    }

    @Test
    void 空响应与null安全() {
        assertTrue(JiraIssueMapper.toPage(null).issues().isEmpty());
        assertTrue(JiraIssueMapper.toPage(mapper.readTree("{}")).issues().isEmpty());
    }

    @Test
    void transitions清单映射跳过缺id脏数据() throws Exception {
        var list = JiraIssueMapper.toTransitions(fixture("transitions.json"));
        assertEquals(2, list.size());
        assertEquals("11", list.get(0).id());
        assertEquals("开始处理", list.get(0).name());
        assertEquals("In Progress", list.get(0).toStatus());
        assertEquals("21", list.get(1).id());
        assertEquals("Done", list.get(1).toStatus());
        // 空响应与 null 安全
        assertTrue(JiraIssueMapper.toTransitions(null).isEmpty());
        assertTrue(JiraIssueMapper.toTransitions(mapper.readTree("{}")).isEmpty());
    }

    @Test
    void 附件按文件名精确匹配() throws Exception {
        JsonNode fields = fixture("issue-attachments.json").get("fields");
        var ref = JiraIssueMapper.toAttachmentRef(fields, "截图 2026-09-01.png");
        assertEquals("https://jira.example.com/secure/attachment/10201/%E6%88%AA%E5%9B%BE+2026-09-01.png",
                ref.contentUrl());
        assertEquals("image/png", ref.mimeType());
        // mimeType 缺失时容忍（content 直链仍在）
        var noMime = JiraIssueMapper.toAttachmentRef(fields, "无mime.bin");
        assertEquals("https://jira.example.com/secure/attachment/10203/%E6%97%A0mime.bin",
                noMime.contentUrl());
        assertNull(noMime.mimeType());
    }

    @Test
    void 附件未命中与空值安全() throws Exception {
        JsonNode fields = fixture("issue-attachments.json").get("fields");
        assertNull(JiraIssueMapper.toAttachmentRef(fields, "不存在.png"));
        assertNull(JiraIssueMapper.toAttachmentRef(fields, null));
        assertNull(JiraIssueMapper.toAttachmentRef(fields, "  "));
        assertNull(JiraIssueMapper.toAttachmentRef(null, "截图 2026-09-01.png"));
        assertNull(JiraIssueMapper.toAttachmentRef(mapper.readTree("{}"), "截图 2026-09-01.png"));
    }

    @Test
    void 创建issue响应映射为外部键与浏览地址() throws Exception {
        var ref = JiraIssueMapper.toIssueRef(fixture("create-issue.json"), "https://jira.example.com/");
        assertEquals("10201", ref.id());
        assertEquals("PROJ-123", ref.key());
        // base_url 尾斜杠归一，拼出可点击地址
        assertEquals("https://jira.example.com/browse/PROJ-123", ref.url());
        // 缺 key 视为脏数据（拿不到外部键就无法登记 link）
        assertNull(JiraIssueMapper.toIssueRef(mapper.readTree("{\"id\":\"1\"}"), "https://jira.example.com"));
        assertNull(JiraIssueMapper.toIssueRef(null, "https://jira.example.com"));
    }

    @Test
    void 单条issue读取复用分页映射() throws Exception {
        JiraIssue issue = JiraIssueMapper.toIssue(fixture("issue-by-key.json"));
        assertEquals("PROJ-123", issue.key());
        assertEquals("需求", issue.issueType());
        assertEquals("Medium", issue.priority());
        assertEquals("待处理", issue.status());
        assertEquals("李四", issue.assignee());
        assertEquals(LocalDate.parse("2026-10-31"), issue.dueDate());
        assertTrue(issue.description().contains("REQ-12"));
        assertTrue(issue.fixVersions().isEmpty());
        // 无 key 的脏响应返回 null（调用方按 NOT_FOUND 处理）
        assertNull(JiraIssueMapper.toIssue(mapper.readTree("{\"id\":\"1\",\"fields\":{}}")));
    }

    @Test
    void 任务类型新端点与旧端点两种形态都能解析() throws Exception {
        var fresh = JiraIssueMapper.toIssueTypes(fixture("createmeta-issuetypes.json"));
        // 缺 id 的脏条目跳过，subtask 原样透出由服务层过滤
        assertEquals(4, fresh.size());
        assertEquals("10001", fresh.get(0).id());
        assertEquals("需求", fresh.get(0).name());
        assertEquals(false, fresh.get(0).subtask());
        assertEquals(true, fresh.get(3).subtask());

        var legacy = JiraIssueMapper.toIssueTypes(fixture("createmeta-legacy.json"));
        assertEquals(2, legacy.size());
        assertEquals("需求", legacy.get(0).name());
        assertEquals(true, legacy.get(1).subtask());

        assertTrue(JiraIssueMapper.toIssueTypes(null).isEmpty());
        assertTrue(JiraIssueMapper.toIssueTypes(mapper.readTree("{}")).isEmpty());
        assertTrue(JiraIssueMapper.toIssueTypes(mapper.readTree("{\"values\":[]}")).isEmpty());
    }

    @Test
    void 优先级与可指派用户映射() throws Exception {
        var priorities = JiraIssueMapper.toPriorities(fixture("priorities.json"));
        // 缺 name 的脏条目跳过（创建 issue 回传的是 name）；缺 id 不影响可用
        assertEquals(3, priorities.size());
        assertEquals("Highest", priorities.get(0).name());
        assertEquals("3", priorities.get(2).id());
        assertTrue(JiraIssueMapper.toPriorities(null).isEmpty());
        assertTrue(JiraIssueMapper.toPriorities(mapper.readTree("{}")).isEmpty());

        var users = JiraIssueMapper.toAssignableUsers(fixture("assignable-users.json"));
        // 缺 name 的脏条目跳过（name 是回传给 Jira 的用户名，没有它无法指派）
        assertEquals(3, users.size());
        assertEquals("lisi", users.get(0).name());
        assertEquals("李四", users.get(0).displayName());
        // displayName 缺失回退 name（部分实例无显示名）
        assertEquals("wangwu", users.get(2).displayName());
        assertTrue(JiraIssueMapper.toAssignableUsers(null).isEmpty());
        assertTrue(JiraIssueMapper.toAssignableUsers(mapper.readTree("{}")).isEmpty());
    }

    @Test
    void 时间解析覆盖无冒号与标准ISO两种偏移() {
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"),
                JiraIssueMapper.parseTime("2026-08-28T09:00:00.000+0800"));
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"),
                JiraIssueMapper.parseTime("2026-08-28T09:00:00.000+08:00"));
        assertNull(JiraIssueMapper.parseTime("不是时间"));
        assertNull(JiraIssueMapper.parseTime(null));
    }

    @Test
    void 创建字段新端点解析出类型与候选值() throws Exception {
        var fields = JiraIssueMapper.toCreateFields(fixture("createmeta-fields.json"));
        assertEquals(11, fields.size());
        var summary = byId(fields, "summary");
        assertEquals("摘要", summary.name());
        assertEquals(true, summary.required());
        assertEquals("string", summary.type());
        assertEquals(false, summary.hasDefault());
        assertTrue(summary.allowedValues().isEmpty());

        // 组件/版本：array + items，候选值含 id 与展示名
        var components = byId(fields, "components");
        assertEquals("array", components.type());
        assertEquals("component", components.items());
        assertEquals(2, components.allowedValues().size());
        assertEquals("10000", components.allowedValues().get(0).id());
        assertEquals("后端", components.allowedValues().get(0).value());

        // option 类自定义字段：展示名取 value（不是 name）
        var flawType = byId(fields, "customfield_10207");
        assertEquals("option", flawType.type());
        assertEquals("功能缺陷", flawType.allowedValues().get(0).value());
        assertEquals("10201", flawType.allowedValues().get(0).id());

        // 级联选择：option 类型但无 allowedValues —— 服务层据此判为渲染不了
        assertTrue(byId(fields, "customfield_10700").allowedValues().isEmpty());

        // hasDefaultValue 与 required 都原样透出（有默认值的必填字段不必让用户填）
        assertTrue(byId(fields, "customfield_10606").hasDefault());
        assertEquals(true, byId(fields, "customfield_10606").required());

        assertTrue(JiraIssueMapper.toCreateFields(null).isEmpty());
        assertTrue(JiraIssueMapper.toCreateFields(mapper.readTree("{}")).isEmpty());
        assertTrue(JiraIssueMapper.toCreateFields(mapper.readTree("{\"values\":[]}")).isEmpty());
    }

    @Test
    void 创建字段旧端点用键名补出fieldId() throws Exception {
        var fields = JiraIssueMapper.toCreateFields(fixture("createmeta-fields-legacy.json"));
        assertEquals(3, fields.size());
        // 旧端点 fields 是「fieldId 为键」的对象，键本身才是 id（对象内不含 fieldId）
        assertEquals("summary", fields.get(0).id());
        var components = byId(fields, "components");
        assertEquals("模块", components.name());
        assertEquals("component", components.items());
        assertEquals(1, components.allowedValues().size());
        assertEquals(false, byId(fields, "customfield_10606").required());
    }

    private static CreateFieldRef byId(List<CreateFieldRef> fields, String id) {
        return fields.stream().filter(f -> id.equals(f.id())).findFirst().orElseThrow();
    }
}
