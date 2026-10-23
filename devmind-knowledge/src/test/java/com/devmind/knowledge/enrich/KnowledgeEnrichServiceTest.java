package com.devmind.knowledge.enrich;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.knowledge.EntryContentChangedEvent;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.EnrichEntryView;
import com.devmind.knowledge.dto.EnrichSubmitView;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeEntryRepository;
import com.devmind.task.api.TaskContext;
import com.devmind.task.service.AsyncTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAP-85 FR-28 增强编排：人工锁定优先、正文版本竞态、失败不阻断、
 * 无端点降级，以及模型输出的宽松解析。
 */
class KnowledgeEnrichServiceTest {

    private KnowledgeEntryRepository entryRepo;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeProperties props;
    private ObjectProvider<ModelEndpointProvider> providers;
    private ModelEndpointProvider provider;
    private EnrichmentModel model;
    private ApplicationEventPublisher eventPublisher;
    private AsyncTaskService taskService;
    private TaskContext ctx;
    private KnowledgeEnrichService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entryRepo = mock(KnowledgeEntryRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        props = new KnowledgeProperties();
        providers = mock(ObjectProvider.class);
        provider = mock(ModelEndpointProvider.class);
        model = mock(EnrichmentModel.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        taskService = mock(AsyncTaskService.class);
        ctx = mock(TaskContext.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(taskService.submit(anyString(), anyString(), any())).thenReturn("task-1");
        service = new KnowledgeEnrichService(entryRepo, kbRepo, props, providers, model,
                eventPublisher, taskService, new ObjectMapper());
    }

    @Test
    void enrichesEntryAndPublishesIndexEvent() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "部署前核对连接串与端口白名单。");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(chat()));
        when(model.complete(any(), anyString(), anyInt())).thenReturn(
                "```json\n{\"docType\":\"maint\",\"summary\":\"部署步骤与核对项\","
                        + "\"tags\":[\"部署\",\"运维\"]}\n```");

        service.execute("{\"entryId\":1}", ctx);

        assertEquals(KnowledgeEntryEntity.DOC_MAINT, entry.getDocType());
        assertEquals("部署步骤与核对项", entry.getSummary());
        assertEquals("部署,运维", entry.getTags());
        assertNotNull(entry.getEnrichedAt());
        assertNull(entry.getEnrichError());
        verify(entryRepo).save(entry);
        verify(eventPublisher).publishEvent(new EntryContentChangedEvent(1L, 10L));
    }

    @Test
    void keepsExistingTagsAndSkipsLockedEntry() {
        KnowledgeEntryEntity locked = entry(1L, "人工改过", "内容");
        locked.setAutoEnrich(false);
        when(entryRepo.findById(1L)).thenReturn(Optional.of(locked));

        service.execute("{\"entryId\":1}", ctx);

        verifyNoInteractions(model);
        assertNull(locked.getEnrichedAt(), "人工锁定条目不得被自动覆盖");
    }

    @Test
    void discardsResultWhenContentChangedDuringModelCall() {
        KnowledgeEntryEntity before = entry(1L, "部署手册", "旧正文");
        KnowledgeEntryEntity fresh = entry(1L, "部署手册", "人工刚改过的新正文");
        fresh.setContentVersion(2);
        when(entryRepo.findById(1L)).thenReturn(Optional.of(before), Optional.of(fresh));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(chat()));
        when(model.complete(any(), anyString(), anyInt())).thenReturn(
                "{\"docType\":\"FAQ\",\"summary\":\"不该落库\",\"tags\":[\"x\"]}");

        service.execute("{\"entryId\":1}", ctx);

        assertEquals(KnowledgeEntryEntity.DOC_TECH, fresh.getDocType(), "竞态结果作废");
        assertNull(fresh.getSummary());
        verify(entryRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void missingEndpointMarksErrorWithoutCallingModel() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "内容");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.empty());

        service.execute("{\"entryId\":1}", ctx);

        verifyNoInteractions(model);
        assertNotNull(entry.getEnrichError());
        assertTrue(entry.getEnrichError().contains("CHAT"), entry.getEnrichError());
        assertNull(entry.getEnrichedAt());
    }

    @Test
    void modelFailureOnlyRecordsErrorAndKeepsValues() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "内容");
        entry.setTags("手工标签");
        entry.setSummary("原摘要");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(chat()));
        when(model.complete(any(), anyString(), anyInt())).thenThrow(new RuntimeException("模型超时"));

        service.execute("{\"entryId\":1}", ctx);

        assertEquals("模型超时", entry.getEnrichError());
        assertEquals("手工标签", entry.getTags());
        assertEquals("原摘要", entry.getSummary());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void illegalModelOutputIsRejected() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "内容");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(chat()));
        when(model.complete(any(), anyString(), anyInt())).thenReturn(
                "{\"docType\":\"UNKNOWN\",\"summary\":\"x\",\"tags\":[]}");

        service.execute("{\"entryId\":1}", ctx);

        assertTrue(entry.getEnrichError().contains("docType 非法"), entry.getEnrichError());
        assertEquals(KnowledgeEntryEntity.DOC_TECH, entry.getDocType());
    }

    @Test
    void endpointMustBeChatKind() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "内容");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        ModelEndpointView embedding = new ModelEndpointView(5L, ModelEndpointView.KIND_EMBEDDING,
                ModelEndpointView.PROVIDER_OPENAI, "向量", "http://x", null, "bge", 1024, 30, 32,
                null, null);
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(embedding));

        service.execute("{\"entryId\":1}", ctx);

        verifyNoInteractions(model);
        assertTrue(entry.getEnrichError().contains("CHAT"));
    }

    @Test
    void emptyContentSkipsModelCall() {
        KnowledgeEntryEntity entry = entry(1L, "空文档", "   ");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(provider.defaultEndpoint(ModelEndpointView.KIND_CHAT)).thenReturn(Optional.of(chat()));

        service.execute("{\"entryId\":1}", ctx);

        verifyNoInteractions(model);
        assertTrue(entry.getEnrichError().contains("正文为空"));
    }

    @Test
    void entriesReturnsLightweightViewsWithDefaultAutoEnrich() {
        when(kbRepo.existsById(5L)).thenReturn(true);
        when(entryRepo.findEnrichEntriesByKbId(eq(5L), any(Pageable.class))).thenReturn(List.of(
                new EnrichRow(1L, 5L, "A", "TECH", null, null, "失败原因", Instant.now()),
                new EnrichRow(2L, 5L, "B", "FAQ", Boolean.FALSE, Instant.now(), null, Instant.now())));

        List<EnrichEntryView> views = service.entries(5L, 999);

        assertEquals(2, views.size());
        assertTrue(views.get(0).autoEnrich(), "历史数据 autoEnrich 为空按允许增强处理");
        assertEquals("失败原因", views.get(0).enrichError());
        assertFalse(views.get(1).autoEnrich());

        var pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(entryRepo).findEnrichEntriesByKbId(eq(5L), pageCaptor.capture());
        assertEquals(200, pageCaptor.getValue().getPageSize(), "limit 上限 200");
    }

    @Test
    void entriesRejectsUnknownKnowledgeBase() {
        when(kbRepo.existsById(99L)).thenReturn(false);

        assertThrows(DevMindException.class, () -> service.entries(99L, 10));
    }

    @Test
    void submitEntryRestoresLockAndQueuesTask() {
        KnowledgeEntryEntity entry = entry(1L, "部署手册", "内容");
        entry.setAutoEnrich(false);
        entry.setEnrichedAt(Instant.now());
        entry.setEnrichError("旧错误");
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));

        EnrichSubmitView view = service.submitEntry(1L);

        assertTrue(entry.getAutoEnrich(), "手工补全恢复自动增强");
        assertNull(entry.getEnrichedAt());
        assertNull(entry.getEnrichError());
        assertEquals(1L, view.entryId());
        assertEquals(10L, view.kbId());
        verify(taskService).submit(eq(KnowledgeEnrichService.TASK_TYPE),
                contains("\"entryId\":1"), any());
    }

    @Test
    void submitRejectsUnknownKnowledgeBase() {
        when(kbRepo.existsById(99L)).thenReturn(false);

        assertThrows(DevMindException.class, () -> service.submit(99L, 10));
        verify(taskService, never()).submit(anyString(), anyString(), any());
    }

    @Test
    void extractJsonToleratesFencesAndSurroundingText() {
        assertEquals("{\"a\":1}", KnowledgeEnrichService.extractJson("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", KnowledgeEnrichService.extractJson("说明\n{\"a\":1}\n结束"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEnrichService.extractJson(null));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEnrichService.extractJson("没有 JSON"));
    }

    @Test
    void mergeTagsDeduplicatesCaseInsensitivelyAndCaps() {
        assertEquals(List.of("部署", "DEPLOY", "运维"), KnowledgeEnrichService.mergeTags(
                List.of("部署", "DEPLOY"), List.of("deploy", "部署", " 运维 ")));

        List<String> many = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l");
        assertEquals(10, KnowledgeEnrichService.mergeTags(List.of(), many).size(), "标签上限 10");

        String longTag = "超长标签".repeat(20);
        assertEquals(32, KnowledgeEnrichService.mergeTags(List.of(), List.of(longTag)).get(0).length());
    }

    @Test
    void autoEnabledReflectsConfiguration() {
        assertFalse(service.autoEnabled(), "默认不自动排队，避免无感产生模型成本");
        props.getEnrich().setEnabled(true);
        assertTrue(service.autoEnabled());
    }

    private static ModelEndpointView chat() {
        return new ModelEndpointView(5L, ModelEndpointView.KIND_CHAT,
                ModelEndpointView.PROVIDER_OPENAI, "对话", "https://api.example.com/v1",
                null, "gpt-4o-mini", null, 30, 32, null, null);
    }

    private static KnowledgeEntryEntity entry(Long id, String name, String content) {
        KnowledgeEntryEntity e = new KnowledgeEntryEntity();
        e.setId(id);
        e.setKbId(10L);
        e.setName(name);
        e.setContentMd(content);
        e.setContentVersion(1);
        e.setDocType(KnowledgeEntryEntity.DOC_TECH);
        e.setAutoEnrich(Boolean.TRUE);
        e.setStatus("active");
        return e;
    }

    private record EnrichRow(Long id, Long kbId, String name, String docType,
                             Boolean autoEnrich, Instant enrichedAt, String enrichError,
                             Instant updatedAt) implements KnowledgeEntryRepository.EnrichEntryRef {
        @Override public Long getId() { return id; }
        @Override public Long getKbId() { return kbId; }
        @Override public String getName() { return name; }
        @Override public String getDocType() { return docType; }
        @Override public Boolean getAutoEnrich() { return autoEnrich; }
        @Override public Instant getEnrichedAt() { return enrichedAt; }
        @Override public String getEnrichError() { return enrichError; }
        @Override public Instant getUpdatedAt() { return updatedAt; }
    }
}
