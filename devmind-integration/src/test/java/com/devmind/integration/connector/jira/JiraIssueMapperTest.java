package com.devmind.integration.connector.jira;

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
    void 时间解析覆盖无冒号与标准ISO两种偏移() {
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"),
                JiraIssueMapper.parseTime("2026-08-28T09:00:00.000+0800"));
        assertEquals(Instant.parse("2026-08-28T01:00:00Z"),
                JiraIssueMapper.parseTime("2026-08-28T09:00:00.000+08:00"));
        assertNull(JiraIssueMapper.parseTime("不是时间"));
        assertNull(JiraIssueMapper.parseTime(null));
    }
}
