package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.knowledge.SolutionSourceProvider;
import com.devmind.common.knowledge.SolutionSourceProvider.SolutionSource;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.knowledge.dto.SolutionSaveRequest;
import com.devmind.knowledge.dto.SolutionSaveResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CAP-84 FR-24：方案沉淀组装（元信息 + 证据附录 + 文档归属参数透传）。 */
class SolutionServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SolutionSourceProvider> providerOf(SolutionSourceProvider provider) {
        ObjectProvider<SolutionSourceProvider> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(provider);
        return op;
    }

    private static SolutionSource source() {
        return new SolutionSource("chat1", "回滚怎么做", "回滚怎么做",
                "## 1. 问题理解\n先摘流量再回滚[S1]。", "qwen2.5", "SOLUTION", Instant.parse("2026-10-24T02:00:00Z"),
                List.of("运维手册库"), List.of(new SolutionSource.Evidence(
                1, 11L, 7L, "发布手册", "maint/runbook.md", 101L, "回滚步骤：先摘流量。", 0.9)));
    }

    @Test
    void 沉淀方案_默认design_元信息与证据附录() {
        DocumentService docs = mock(DocumentService.class);
        when(docs.create(any())).thenReturn(new DocDetail(9L, "design", null, null, null,
                "回滚怎么做 方案", 1, "draft", List.of(), "", "", "tester", Instant.now(), Instant.now()));
        SolutionSourceProvider provider = mock(SolutionSourceProvider.class);
        when(provider.find("chat1", null)).thenReturn(Optional.of(source()));

        SolutionService service = new SolutionService(docs, providerOf(provider));
        SolutionSaveResult result = service.save("chat1", null);

        assertEquals(9L, result.documentId());
        ArgumentCaptor<DocRequest> captor = ArgumentCaptor.forClass(DocRequest.class);
        verify(docs).create(captor.capture());
        DocRequest req = captor.getValue();
        assertEquals("design", req.kind());
        assertEquals("回滚怎么做 方案", req.title());
        assertEquals(List.of("知识库方案"), req.tags());
        assertTrue(req.contentMd().contains("生成时间："), "元信息：生成时间");
        assertTrue(req.contentMd().contains("模型：qwen2.5"), "元信息：模型");
        assertTrue(req.contentMd().contains("知识库：运维手册库"), "元信息：知识库");
        assertTrue(req.contentMd().contains("引用：1 条"), "元信息：引用数");
        assertTrue(req.contentMd().contains("[查看会话](/chats?cid=chat1)"), "会话链接");
        assertTrue(req.contentMd().contains("## 证据附录"), "证据附录");
        assertTrue(req.contentMd().contains("### [S1] 发布手册"), "引用来源");
        assertTrue(req.contentMd().contains("回滚步骤：先摘流量。"), "证据片段");
    }

    @Test
    void 沉淀方案_指定report与项目需求_非法kind拒绝() {
        DocumentService docs = mock(DocumentService.class);
        when(docs.create(any())).thenReturn(new DocDetail(9L, "report", "req1", null, "proj",
                "标题", 1, "draft", List.of(), "", "", "tester", Instant.now(), Instant.now()));
        SolutionSourceProvider provider = mock(SolutionSourceProvider.class);
        when(provider.find("chat1", 3L)).thenReturn(Optional.of(source()));
        SolutionService service = new SolutionService(docs, providerOf(provider));

        service.save("chat1", new SolutionSaveRequest(3L, "标题", "REPORT", "proj", "req1", null));
        ArgumentCaptor<DocRequest> captor = ArgumentCaptor.forClass(DocRequest.class);
        verify(docs).create(captor.capture());
        assertEquals("report", captor.getValue().kind());
        assertEquals("proj", captor.getValue().projectId());
        assertEquals("req1", captor.getValue().requirementId());

        assertThrows(DevMindException.class, () -> service.save("chat1",
                new SolutionSaveRequest(null, null, "requirement", null, null, null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 沉淀方案_模块未装配或轮次无回答() {
        DocumentService docs = mock(DocumentService.class);
        ObjectProvider<SolutionSourceProvider> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        assertThrows(DevMindException.class, () -> new SolutionService(docs, absent).save("chat1", null));

        SolutionSourceProvider provider = mock(SolutionSourceProvider.class);
        when(provider.find("chat1", null)).thenReturn(Optional.empty());
        assertThrows(DevMindException.class,
                () -> new SolutionService(docs, providerOf(provider)).save("chat1", null));
    }
}
