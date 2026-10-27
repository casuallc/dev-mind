package com.devmind.knowledge.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolCall;
import com.devmind.common.agent.tool.ToolContext;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.retrieve.KnowledgeSearchService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-94 FR-05：工具显式选库。不传 kbIds → 不检索，返回 active 库清单供模型选库；
 * kbIds 非整数/超 5 个 → INVALID_ARGUMENTS；传了 kbIds 才走混合检索。
 */
class KnowledgeToolsTest {

    private KnowledgeSearchService searchService;
    private KnowledgeBaseRepository kbRepo;
    private PlatformTool tool;

    private static final ToolContext CTX = new ToolContext(
            "t1", null, "tester", "ADMIN", null, null, null, null, "p1", "项目1", Instant.now());

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        tool = new KnowledgeTools(searchService, kbRepo).searchKnowledgeTool();
    }

    private static KnowledgeBaseEntity kb(long id, String name) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription("描述-" + name);
        kb.setStatus(KnowledgeBaseEntity.STATUS_ACTIVE);
        return kb;
    }

    private ToolResult call(Map<String, Object> args) {
        return tool.execute(new ToolCall("c1", "search_knowledge", args), CTX);
    }

    @Test
    void noKbIdsReturnsBaseListWithoutSearching() {
        when(kbRepo.findByStatusOrderByCreatedAtDesc(KnowledgeBaseEntity.STATUS_ACTIVE))
                .thenReturn(List.of(kb(1, "研发规范"), kb(2, "运维经验")));

        ToolResult result = call(Map.of("query", "回滚"));

        assertNull(result.errorCode());
        assertTrue(result.summary().contains("请先指定要检索的知识库"), result.summary());
        assertTrue(result.summary().contains("[1] 研发规范"), result.summary());
        assertTrue(result.summary().contains("[2] 运维经验"), result.summary());
        assertNotNull(result.data());
        verify(searchService, never()).searchDocuments(any());
    }

    @Test
    void noActiveBasesSaysSo() {
        when(kbRepo.findByStatusOrderByCreatedAtDesc(KnowledgeBaseEntity.STATUS_ACTIVE))
                .thenReturn(List.of());

        ToolResult result = call(Map.of("query", "回滚"));

        assertNull(result.errorCode());
        assertTrue(result.summary().contains("还没有任何启用中的知识库"), result.summary());
    }

    @Test
    void tooManyKbIdsRejected() {
        ToolResult result = call(Map.of("query", "回滚", "kbIds", List.of(1, 2, 3, 4, 5, 6)));

        assertEquals(ToolResult.INVALID_ARGUMENTS, result.errorCode());
        verify(searchService, never()).searchDocuments(any());
    }

    @Test
    void nonNumericKbIdRejected() {
        ToolResult result = call(Map.of("query", "回滚", "kbIds", List.of("abc")));

        assertEquals(ToolResult.INVALID_ARGUMENTS, result.errorCode());
    }

    @Test
    void boundKbIdsDriveSearch() {
        when(searchService.searchDocuments(any())).thenReturn(new KnowledgeSearchV2Response(
                List.of(), 0, false, null));

        ToolResult result = call(Map.of("query", "回滚", "kbIds", List.of(1, 1, 2)));

        assertNull(result.errorCode());
        assertTrue(result.summary().contains("没有检索到相关知识条目"), result.summary());
        org.mockito.ArgumentCaptor<KnowledgeSearchV2Request> captor =
                org.mockito.ArgumentCaptor.forClass(KnowledgeSearchV2Request.class);
        verify(searchService).searchDocuments(captor.capture());
        assertEquals(List.of(1L, 2L), captor.getValue().kbIds(), "重复 id 去重");
    }

    @Test
    void missingQueryRejected() {
        ToolResult result = call(Map.of("kbIds", List.of(1)));

        assertEquals(ToolResult.INVALID_ARGUMENTS, result.errorCode());
    }
}
