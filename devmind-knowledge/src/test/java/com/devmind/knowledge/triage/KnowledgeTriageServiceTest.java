package com.devmind.knowledge.triage;

import com.devmind.common.decision.DecisionAnswer;
import com.devmind.common.decision.DecisionEngine;
import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.decision.DecisionResult;
import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.knowledge.dto.TriageStatusView;
import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import com.devmind.knowledge.repo.KnowledgeBaseRepository;
import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CAP-55 FR-04 分诊服务（mock 掉的引擎/召回器/落库，不拉 Spring）：
 * 盯住三件事——发给模型的题面与 state 是什么、落库快照里存了什么、
 * 以及各层降级（没引擎/召回失败/落库失败）都不许把异常抛给调用方。
 */
class KnowledgeTriageServiceTest {

    private static final long PROPOSAL_ID = 42L;

    private final KnowledgeProposalRepository proposalRepo = mock(KnowledgeProposalRepository.class);
    private final KnowledgeBaseRepository kbRepo = mock(KnowledgeBaseRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final KnowledgeTriageWriter writer = mock(KnowledgeTriageWriter.class);
    private final DecisionRecordSink sink = mock(DecisionRecordSink.class);

    /** 引擎收到的入参（断言"实际发出去的是什么"比断言结果更能钉住契约） */
    private Map<String, Object> sentState;
    private Map<String, Map<String, Object>> sentQuestions;
    private String recallQuery;
    private int recallTopK;
    private KnowledgeProposalEntity proposal;

    @BeforeEach
    void setUp() {
        proposal = new KnowledgeProposalEntity();
        proposal.setId(PROPOSAL_ID);
        proposal.setTitle("构建失败先看日志 Hub 末尾");
        proposal.setContentMd("环境类报错九成在日志末尾 200 行");
        proposal.setTargetScope("project");
        proposal.setTargetProjectId("p1");
        proposal.setStatus("open");
        proposal.setCreatedAt(Instant.now());
        when(proposalRepo.findById(PROPOSAL_ID)).thenReturn(Optional.of(proposal));

        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(7L);
        when(kbRepo.findAll()).thenReturn(List.of(kb));
        when(projectService.requireProject("p1"))
                .thenReturn(new Project("p1", "商城", null, null, List.of(), null, null, null));
    }

    // ---------------- 装配 ----------------

    private static DecisionEngine engine(DecisionResult result) {
        return new DecisionEngine() {
            @Override
            public DecisionResult decide(Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions) {
                return result;
            }

            @Override
            public Optional<String> unavailableReason() {
                return Optional.empty();
            }
        };
    }

    private DecisionEngine recordingEngine(DecisionResult result) {
        return new DecisionEngine() {
            @Override
            public DecisionResult decide(Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions) {
                sentState = state;
                sentQuestions = questions;
                return result;
            }

            @Override
            public Optional<String> unavailableReason() {
                return Optional.empty();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private KnowledgeTriageService service(DecisionEngine engine, KnowledgeRetriever retriever) {
        return new KnowledgeTriageService(proposalRepo, kbRepo, projectService,
                providerOf(retriever), providerOf(engine), providerOf(sink), writer);
    }

    private static DecisionResult okResult() {
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        answers.put(TriageQuestions.Q_LAYER, new DecisionAnswer("choice", "project",
                0.9, null, 0.9, Map.of("global", 0.05, "project", 0.9, "discard", 0.05)));
        answers.put(TriageQuestions.Q_DUPLICATE, new DecisionAnswer("noul", null,
                null, 0.12, 0.8, Map.of()));
        answers.put(TriageQuestions.Q_QUALITY, new DecisionAnswer("score", null,
                2.0, null, 0.8, Map.of("0", 0.1, "1", 0.1, "2", 0.8)));
        return DecisionResult.ok(answers, "multilingual", "non-Latin script", 320);
    }

    private KnowledgeRetriever retrieverOf(List<KnowledgeRetriever.RetrievedChunk> chunks) {
        return new KnowledgeRetriever() {
            @Override
            public boolean vectorAvailable() {
                return true;
            }

            @Override
            public List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK) {
                return chunks;
            }

            @Override
            public Optional<KbOverview> overview(long kbId) {
                return Optional.empty();
            }

            @Override
            public Detailed retrieveDetailed(List<Long> kbIds, String query, int topK) {
                recallQuery = query;
                recallTopK = topK;
                return new Detailed(chunks, true, DegradedReason.NONE);
            }
        };
    }

    private static KnowledgeRetriever.RetrievedChunk chunk(long id, String name, double score) {
        return new KnowledgeRetriever.RetrievedChunk(id, name, 7L, "正文片段", score);
    }

    // ---------------- 成功路径 ----------------

    @Test
    void triageAsksThreeTypedQuestionsAboutTheProposal() {
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertFalse(result.degraded(), result.degradedReason());
        assertEquals(3, sentQuestions.size(), "三题：层级 / 重复 / 质量");
        assertEquals("choice", sentQuestions.get(TriageQuestions.Q_LAYER).get("type"));
        assertEquals("noul", sentQuestions.get(TriageQuestions.Q_DUPLICATE).get("type"));
        assertEquals("score", sentQuestions.get(TriageQuestions.Q_QUALITY).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) sentQuestions.get(TriageQuestions.Q_LAYER)
                .get("criteria");
        assertEquals(List.of("global", "project", "discard"), List.copyOf(criteria.keySet()),
                "选项 key 是机器值，与 adopt API 的 target 同域");
        assertEquals(TriageQuestions.standard(), sentQuestions, "题面来自 TriageQuestions 单一来源");

        assertEquals("构建失败先看日志 Hub 末尾", sentState.get("proposal_title"));
        assertEquals("环境类报错九成在日志末尾 200 行", sentState.get("proposal_content"));
        assertTrue(String.valueOf(sentState.get("project")).contains("商城"), sentState.get("project").toString());
        assertFalse(sentState.containsKey("declared_target"),
                "不放提案人自称的去向：模型的价值就在于不同意他，喂进去等于给锚");
    }

    @Test
    void snapshotStoresModelFactsAndRetrievalEvidence() {
        KnowledgeRetriever retriever = retrieverOf(List.of(chunk(11L, "日志排查", 0.81)));
        KnowledgeTriageService service = service(recordingEngine(okResult()), retriever);

        service.triage(PROPOSAL_ID);

        assertTrue(String.valueOf(sentState.get("similar_entries")).contains("日志排查"),
                "召回片段要进 state，否则重复判定没有判据: " + sentState.get("similar_entries"));
        assertTrue(String.valueOf(sentState.get("similar_entries")).contains("0.81"));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(writer).write(eq(PROPOSAL_ID), stored.capture(), eq(false));
        String json = stored.getValue();
        assertTrue(json.contains("\"model\":\"multilingual\""), json);
        assertTrue(json.contains("\"routingReason\":\"non-Latin script\""), json);
        assertTrue(json.contains("\"retrieval\":\"vector\""), json);
        assertTrue(json.contains("\"entryName\":\"日志排查\""), json);
        assertTrue(json.contains("\"adopt_layer\""), "answers 原样落库，抽屉要展示原文: " + json);
        assertFalse(json.contains("已截断"));
    }

    @Test
    void suggestionIsRecordedForTheTrainingSet() {
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        service.triage(PROPOSAL_ID);

        verify(sink).saveSuggestion(eq(KnowledgeTriageService.CAPABILITY), eq(String.valueOf(PROPOSAL_ID)),
                eq(sentState), eq(sentQuestions), any(DecisionResult.class));
    }

    @Test
    void oversizedContentIsCappedBeforeItLeaves() {
        proposal.setContentMd("中文".repeat(2000));
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        service.triage(PROPOSAL_ID);

        String content = String.valueOf(sentState.get("proposal_content"));
        assertTrue(content.contains("（已截断）"), "自己先截到位，落进训练集的 state 才与模型看到的逐字一致");
        assertTrue(content.length() < 2000, "截断后不该还是原文长度: " + content.length());
    }

    // ---------------- 降级链 ----------------

    @Test
    void degradedResultIsStillPersistedWithReason() {
        DecisionResult degraded = DecisionResult.degraded(
                "决策调用 http://127.0.0.1:8377/v1/predict 失败: ConnectException", 3000);
        KnowledgeTriageService service = service(recordingEngine(degraded), retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertTrue(result.degraded());
        verify(writer).write(eq(PROPOSAL_ID), contains("ConnectException"), eq(true));
        verify(sink).saveSuggestion(anyString(), anyString(), any(), any(), eq(degraded));
    }

    @Test
    void missingEngineDegradesWithoutCallingTheSink() {
        KnowledgeTriageService service = service(null, retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertTrue(result.degraded());
        assertTrue(result.degradedReason().contains("未装配"), result.degradedReason());
        assertNull(sentState, "没引擎就不该有调用");
        verify(writer).write(eq(PROPOSAL_ID), contains("未装配"), eq(true));
        verify(sink, never()).saveSuggestion(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void missingProposalIsNotAnError() {
        when(proposalRepo.findById(PROPOSAL_ID)).thenReturn(Optional.empty());
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertTrue(result.degraded());
        verifyNoInteractions(writer);
    }

    @Test
    void retrievalFailureIsCaughtAndNotedAsWeakEvidence() {
        KnowledgeRetriever exploding = new KnowledgeRetriever() {
            @Override
            public boolean vectorAvailable() {
                return true;
            }

            @Override
            public List<RetrievedChunk> retrieve(List<Long> kbIds, String query, int topK) {
                throw new IllegalStateException("索引库连接不上");
            }

            @Override
            public Optional<KbOverview> overview(long kbId) {
                return Optional.empty();
            }
        };
        KnowledgeTriageService service = service(recordingEngine(okResult()), exploding);

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertFalse(result.degraded(), "召回挂了不该让分诊整体降级（层级/质量两题仍有效）");
        assertTrue(String.valueOf(sentState.get("similar_entries")).contains("检索失败"),
                sentState.get("similar_entries").toString());
    }

    @Test
    void recallUsesTheTitleWithTopThreeSoTheKeywordFallbackCanHit() {
        KnowledgeTriageService service = service(recordingEngine(okResult()),
                retrieverOf(List.of(chunk(11L, "日志排查", 0.81))));

        service.triage(PROPOSAL_ID);

        assertEquals("构建失败先看日志 Hub 末尾", recallQuery,
                "LIKE 兜底是整串匹配：拿全文当 query 会让'没配 embedding'时永远零命中");
        assertEquals(3, recallTopK, "CAP-55：top3 相似条目");
    }

    @Test
    void missingRetrieverStillDecides() {
        KnowledgeTriageService service = service(recordingEngine(okResult()), null);

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertFalse(result.degraded());
        assertTrue(String.valueOf(sentState.get("similar_entries")).contains("未装配"));
    }

    @Test
    void sinkFailureDoesNotBreakTriage() {
        doThrow(new IllegalStateException("decision_records 表锁了"))
                .when(sink).saveSuggestion(anyString(), anyString(), any(), any(), any());
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertFalse(result.degraded(), "记不上样本是数据飞轮的损失，不是分诊的失败");
        verify(writer).write(eq(PROPOSAL_ID), anyString(), eq(false));
    }

    @Test
    void writerFailureTurnsTheResultIntoDegraded() {
        doThrow(new IllegalStateException("库连不上"))
                .when(writer).write(eq(PROPOSAL_ID), anyString(), anyBoolean());
        KnowledgeTriageService service = service(recordingEngine(okResult()), retrieverOf(List.of()));

        DecisionResult result = service.triage(PROPOSAL_ID);

        assertTrue(result.degraded(), "没落库就等于没分诊，结果必须如实说");
        assertTrue(result.degradedReason().contains("落库失败"), result.degradedReason());
        assertEquals(320, result.latencyMs(), "耗时是模型那次往返的真实值，别被写库失败抹掉");
    }

    // ---------------- 可用性（FR-07 置灰） ----------------

    @Test
    void statusReportsUnavailableWithoutEngine() {
        TriageStatusView status = service(null, retrieverOf(List.of())).status();

        assertFalse(status.available());
        assertTrue(status.reason().contains("未装配"), status.reason());
    }

    @Test
    void statusIsAvailableWhenEngineHasNoComplaint() {
        TriageStatusView status = service(engine(okResult()), retrieverOf(List.of())).status();

        assertTrue(status.available(), status.reason());
        assertEquals("", status.reason());
    }

    @Test
    void statusSurfacesEndpointReason() {
        DecisionEngine blocked = new DecisionEngine() {
            @Override
            public DecisionResult decide(Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions) {
                throw new AssertionError("置灰状态下不应被调用");
            }

            @Override
            public Optional<String> unavailableReason() {
                return Optional.of("未配置平台默认决策端点：请在「后台 → 模型接入」登记 kind=决策 的端点并设为默认");
            }
        };

        TriageStatusView status = service(blocked, retrieverOf(List.of())).status();

        assertFalse(status.available());
        assertTrue(status.reason().contains("模型接入"), status.reason());
    }

    @Test
    void statusNeverThrowsEvenIfEngineMisbehaves() {
        DecisionEngine broken = new DecisionEngine() {
            @Override
            public DecisionResult decide(Map<String, Object> state,
                                         Map<String, Map<String, Object>> questions) {
                throw new AssertionError("不该被调用");
            }

            @Override
            public Optional<String> unavailableReason() {
                throw new IllegalStateException("第三方实现炸了");
            }
        };

        TriageStatusView status = service(broken, retrieverOf(List.of())).status();

        assertFalse(status.available(), "状态端点不能 500：UI 拿它决定灰不灰");
        assertNotNull(status.reason());
    }
}
