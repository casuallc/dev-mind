package com.devmind.decisionlab.dataset;

import com.devmind.auth.IdentityService;
import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.dataset.dto.DatasetDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemRequest;
import com.devmind.decisionlab.dataset.dto.DatasetRequest;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decision.record.model.DecisionRecordEntity;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import com.devmind.decisionlab.dataset.dto.RecordsIntakeResult;
import com.devmind.decisionlab.dataset.dto.RecordsIntakeRequest;
import com.devmind.decisionlab.dataset.dto.RecordsPreview;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-56 FR-02 服务层：把「红线」逐条测成可证伪的断言。
 *
 * <p><b>为什么这里手搓了一层内存仓储而不是起 Spring 上下文</b>：Spring Boot 4.1.1 的
 * {@code spring-boot-test-autoconfigure} 里没有 JPA 切片（连 {@code @DataJpaTest} 这个类都不存在），
 * 全项目也没有一处切片测试。为一个纯业务校验的服务层去引一件本仓库不用的测试基建，代价大于收益；
 * 而这里要钉的恰恰是<b>业务判断</b>（该不该拒、拒的理由说不说得清）——那正是 mock 掉仓储后
 * 剩下的部分。JPQL / 派生方法名的语法校验不在这一层，它由上下文启动兜住（见 Phase 12 E2E）。</p>
 *
 * <p>用例的取舍：每条都对着 CAP-56 里一处「静默出错就会毁掉整套指标」的地方——
 * 对照组标签与内容不符、认不出的 caseGroup 被当成普通样本、冻结后还能就地改条目、
 * 题面版本没戳上导致报告测的题面无从考据。</p>
 */
class DatasetServiceTest {

    private static final String GOOD_CONTENT = "把日志按天切分并归档，避免单文件过大";
    private static final String OTHER_CONTENT = "容器网络的 MTU 问题会让大包被静默丢掉";

    private final DecisionDatasetRepository datasetRepo = mock(DecisionDatasetRepository.class);
    private final DecisionDatasetItemRepository itemRepo = mock(DecisionDatasetItemRepository.class);
    private final DecisionRecordRepository recordRepo = mock(DecisionRecordRepository.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final DatasetJson json = new DatasetJson(new ObjectMapper());

    /** 内存里的三张"表"：够真实到能覆盖 save/find/delete 的往返，又不带 JPA 的启动成本 */
    private final Map<Long, DecisionDatasetEntity> datasets = new LinkedHashMap<>();
    private final Map<Long, DecisionDatasetItemEntity> items = new LinkedHashMap<>();
    private final Map<Long, DecisionRecordEntity> records = new LinkedHashMap<>();
    private long datasetSeq;
    private long itemSeq;

    private DatasetService service;

    @BeforeEach
    void setUp() {
        when(identity.currentActor()).thenReturn("tester");

        when(datasetRepo.save(any(DecisionDatasetEntity.class))).thenAnswer(inv -> {
            DecisionDatasetEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++datasetSeq);
            }
            datasets.put(e.getId(), e);
            return e;
        });
        when(datasetRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(datasets.get(inv.<Long>getArgument(0))));
        when(datasetRepo.findByNameOrderByVersionDesc(anyString())).thenAnswer(inv -> datasets.values().stream()
                .filter(d -> inv.getArgument(0).equals(d.getName()))
                .sorted(Comparator.comparingInt(DecisionDatasetEntity::getVersion).reversed())
                .toList());
        doAnswer(inv -> {
            datasets.remove(((DecisionDatasetEntity) inv.getArgument(0)).getId());
            return null;
        }).when(datasetRepo).delete(any(DecisionDatasetEntity.class));

        when(itemRepo.save(any(DecisionDatasetItemEntity.class))).thenAnswer(inv -> {
            DecisionDatasetItemEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++itemSeq);
            }
            items.put(e.getId(), e);
            return e;
        });
        when(itemRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(items.get(inv.<Long>getArgument(0))));
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenAnswer(inv -> rowsOf(inv.getArgument(0)));
        when(itemRepo.countByDatasetId(anyLong())).thenAnswer(inv -> (long) rowsOf(inv.getArgument(0)).size());
        doAnswer(inv -> {
            items.values().removeIf(r -> r.getDatasetId().equals(inv.getArgument(0)));
            return null;
        }).when(itemRepo).deleteByDatasetId(anyLong());
        doAnswer(inv -> {
            items.remove(((DecisionDatasetItemEntity) inv.getArgument(0)).getId());
            return null;
        }).when(itemRepo).delete(any(DecisionDatasetItemEntity.class));
        when(itemRepo.collectedRecordIds(anyLong())).thenAnswer(inv -> rowsOf(inv.getArgument(0)).stream()
                .map(DecisionDatasetItemEntity::getOriginRecordId)
                .filter(Objects::nonNull)
                .toList());

        when(recordRepo.findForIntake(any(), any(), any(Pageable.class))).thenAnswer(inv -> {
            String capability = inv.getArgument(0);
            Instant since = inv.getArgument(1);
            Pageable pageable = inv.getArgument(2);
            List<DecisionRecordEntity> hit = records.values().stream()
                    .filter(r -> capability == null || capability.equals(r.getCapability()))
                    .filter(r -> since == null || (r.getCreatedAt() != null && !r.getCreatedAt().isBefore(since)))
                    .sorted(Comparator.comparing(DecisionRecordEntity::getId))
                    .toList();
            return new PageImpl<>(hit, pageable, hit.size());
        });

        service = new DatasetService(datasetRepo, itemRepo, recordRepo, json, identity);
    }

    // ---------------- 建集 ----------------

    @Test
    void createStartsAtVersionOneAsADraft() {
        DatasetDetail detail = service.create(new DatasetRequest("分诊基准集", "BENCHMARK", "人为标"));

        assertEquals(1, detail.dataset().version());
        assertFalse(detail.dataset().frozen());
        assertEquals(0, detail.dataset().itemCount());
        assertEquals("tester", detail.dataset().createdBy());
        // 四个组都要出现在计数里（缺的记 0）：界面才会显示"缺对照·不相关"而不是干脆不显示
        assertEquals(List.copyOf(CaseGroups.ALL), List.copyOf(detail.caseGroupCounts().keySet()));
        assertTrue(detail.caseGroupCounts().values().stream().allMatch(c -> c == 0L));
        assertTrue(detail.manifest().isEmpty(), "草稿没有旁证，不该给一个看着像旁证的空壳");
    }

    @Test
    void createRejectsADuplicateNameAndPointsAtRevise() {
        service.create(new DatasetRequest("分诊基准集", "BENCHMARK", null));

        DevMindException e = conflict(() ->
                service.create(new DatasetRequest("分诊基准集", "BENCHMARK", null)));
        assertTrue(e.getMessage().contains("修订为新版本"), "要告诉人「同名改内容」该怎么走：" + e.getMessage());
    }

    @Test
    void createRejectsAnUnknownKindInsteadOfGuessing() {
        DevMindException e = badRequest(() -> service.create(new DatasetRequest("集", "benchmark2", null)));
        assertTrue(e.getMessage().contains("BENCHMARK"));
    }

    @Test
    void updateRefusesRenamingBecauseTheReviseChainIsKeyedByName() {
        Long id = service.create(new DatasetRequest("分诊基准集", "BENCHMARK", null)).dataset().id();

        DevMindException e = badRequest(() ->
                service.update(id, new DatasetRequest("换个名字", "BENCHMARK", null)));
        assertTrue(e.getMessage().contains("名不可修改"), e.getMessage());
    }

    // ---------------- 标注校验 ----------------

    @Test
    void markingAnItemStampsTheQuestionSetVersionSoOldReportsStayComparable() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        DatasetItemDetail item = service.addItem(id, normalItem());

        assertEquals("project", item.gold().get(TriageQuestions.Q_LAYER));
        assertEquals(List.of(), item.goldNotLanded());
        // 题面版本戳在条目上（不是建集时写死）：将来题面升版，旧样本仍留着旧版本戳，
        // 于是"混版本"会在冻结时被抓住，而不是被悄悄对齐到新题面
        assertEquals(TriageQuestions.VERSION, rowsOf(id).get(0).getQuestionSetVersion());
    }

    @Test
    void markingRejectsAnUnknownCaseGroupInsteadOfSilentlyTreatingItAsNormal() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        // 拼错一个字母若被吞成 NORMAL，对照组红线就被静默绕过了
        DevMindException e = badRequest(() -> service.addItem(id,
                new DatasetItemRequest(state(GOOD_CONTENT, "1. 《X》\n别的"), null, fullGold(), "EMPTY_RECAL", null)));
        assertTrue(e.getMessage().contains("EMPTY_RECALL"), e.getMessage());
    }

    @Test
    void markingRejectsAControlLabelThatItsOwnStateContradicts() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        // 打了"空召回"却塞了三条召回结果——统计上照样算对照组齐备，只有读内容才抓得住
        DevMindException e = conflict(() -> service.addItem(id, item(CaseGroups.EMPTY_RECALL,
                state(GOOD_CONTENT, "1. 《日志归档》\n把日志按天切分并归档"), fullGold())));
        assertTrue(e.getMessage().contains(TriageQuestions.EMPTY_RECALL), e.getMessage());
    }

    @Test
    void markingRejectsAVerbatimDuplicateGroupWhoseRecallIsNotVerbatim() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        DevMindException e = conflict(() -> service.addItem(id, item(CaseGroups.VERBATIM_DUP,
                state(GOOD_CONTENT, "1. 《日志归档》\n把日志按天切分并归档，避免单文件太大"), fullGold())));
        assertTrue(e.getMessage().contains("逐字"), e.getMessage());
    }

    @Test
    void markingRejectsAQuestionSetThatIsNotTheStandardOne() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        Map<String, Object> questions = new LinkedHashMap<>(TriageQuestions.standard());
        questions.put("extra_question", Map.of("type", "noul"));

        DevMindException e = badRequest(() -> service.addItem(id,
                new DatasetItemRequest(state(GOOD_CONTENT, "1. 《X》\n无关"), questions, fullGold(), null, null)));
        assertTrue(e.getMessage().contains("extra_question"), "要说清差在哪一题：" + e.getMessage());
    }

    @Test
    void markingRejectsGoldThatDoesNotLandOnTheQuestionSet() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        // 选项名写错（"PROJECT" 不在 criteria 里）→ 这题等于没标，必须当场拒而不是留到跑评测
        DevMindException e = badRequest(() -> service.addItem(id, new DatasetItemRequest(
                state(GOOD_CONTENT, "1. 《X》\n无关"), null, Map.of(TriageQuestions.Q_LAYER, "PROJECT"), null, null)));
        assertTrue(e.getMessage().contains("落不上题面"), e.getMessage());
    }

    @Test
    void replacingAnItemOverwritesAllThreeJsonBlobs() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        Long itemId = service.addItem(id, normalItem()).item().id();

        DatasetItemDetail replaced = service.replaceItem(id, itemId, item(CaseGroups.IRRELEVANT,
                state(GOOD_CONTENT, "1. 《容器网络》\n" + OTHER_CONTENT), Map.of(
                        TriageQuestions.Q_LAYER, "discard",
                        TriageQuestions.Q_DUPLICATE, false,
                        TriageQuestions.Q_QUALITY, 2)));

        assertEquals(CaseGroups.IRRELEVANT, replaced.item().caseGroup());
        assertEquals("discard", replaced.gold().get(TriageQuestions.Q_LAYER));
        assertEquals(itemId, replaced.item().id(), "替换不换行，还是同一条样本");
        assertEquals(1, rowsOf(id).size());
    }

    // ---------------- 冻结 ----------------

    @Test
    void freezeRefusesWhenControlGroupsAreMissing() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        service.addItem(id, normalItem());

        DevMindException e = conflict(() -> service.freeze(id));
        // 三个组一个都没有 → 三个都要点名（缺哪个与缺哪三个，处置是一样的：继续标）
        for (String group : CaseGroups.CONTROL) {
            assertTrue(e.getMessage().contains(CaseGroups.label(group)),
                    "要点名缺的是 " + group + "：" + e.getMessage());
        }
        assertFalse(service.detail(id).dataset().frozen(), "拒绝冻结就是真的没冻上");
    }

    @Test
    void freezeRefusesWhenAControlLabelDoesNotHoldEvenThoughAllGroupsArePresent() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        service.addItem(id, item(CaseGroups.VERBATIM_DUP, state(GOOD_CONTENT,
                "1. 《日志归档》\n" + GOOD_CONTENT), fullGold()));
        service.addItem(id, item(CaseGroups.IRRELEVANT, state(GOOD_CONTENT,
                "1. 《容器网络》\n" + OTHER_CONTENT), fullGold()));
        // 三类都在计数上，但这一条是"历史数据"：绕过标注校验直接落库，模拟被改过的库/导入集
        Long badId = inject(id, CaseGroups.EMPTY_RECALL, state(GOOD_CONTENT, "1. 《X》\n有召回内容"),
                fullGold(), TriageQuestions.VERSION);

        DevMindException e = conflict(() -> service.freeze(id));
        assertTrue(e.getMessage().contains("#" + badId), "要点名到条目 id：" + e.getMessage());
        assertTrue(e.getMessage().contains("空召回"), e.getMessage());
    }

    @Test
    void freezeStampsItemCountQuestionSetVersionAndTheManifest() {
        Long id = completeDataset();

        DatasetDetail frozen = service.freeze(id);

        assertTrue(frozen.dataset().frozen());
        assertEquals(TriageQuestions.VERSION, frozen.dataset().questionSetVersion());
        assertEquals(3, frozen.dataset().itemCount());
        assertEquals("tester", frozen.dataset().frozenBy());
        assertEquals(3, frozen.manifest().get("itemCount"));
        assertEquals(TriageQuestions.VERSION, frozen.manifest().get("questionSetVersion"));
        assertTrue(frozen.warnings().isEmpty(), "冻过就不再提醒：拿今天的口径去提醒过去的集只会误导");
        // 三类对照组各一条；这份集里没有普通样本（NORMAL 记 0 而不是不出现）
        for (String group : CaseGroups.CONTROL) {
            assertEquals(1L, frozen.caseGroupCounts().get(group), group);
        }
        assertEquals(0L, frozen.caseGroupCounts().get(CaseGroups.NORMAL));
    }

    @Test
    void freezeRefusesWhenSomeSamplesCarryNoQuestionSetVersion() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        inject(id, CaseGroups.VERBATIM_DUP, verbatimState(), fullGold(), TriageQuestions.VERSION);
        inject(id, CaseGroups.IRRELEVANT, irrelevantState(), fullGold(), TriageQuestions.VERSION);
        inject(id, CaseGroups.EMPTY_RECALL, emptyRecallState(), fullGold(), null);

        DevMindException e = conflict(() -> service.freeze(id));
        // 没戳版本的样本一旦混进来，"这份报告测的是哪套题面"就无从考据
        assertTrue(e.getMessage().contains("题面版本"), e.getMessage());
    }

    @Test
    void freezeRefusesAnAlreadyFrozenDataset() {
        Long id = completeDataset();
        service.freeze(id);

        assertTrue(conflict(() -> service.freeze(id)).getMessage().contains("已冻结"));
    }

    @Test
    void everyWriteToAFrozenDatasetIsRejectedAndPointsAtRevise() {
        Long id = completeDataset();
        service.freeze(id);
        Long itemId = rowsOf(id).get(0).getId();

        for (Runnable write : List.<Runnable>of(
                () -> service.addItem(id, normalItem()),
                () -> service.replaceItem(id, itemId, normalItem()),
                () -> service.deleteItem(id, itemId),
                () -> service.update(id, new DatasetRequest("集", "BENCHMARK", null)),
                () -> service.delete(id))) {
            DevMindException e = conflict(write);
            assertTrue(e.getMessage().contains("修订为新版本"),
                    "冻结集的写操作要说清该怎么办：" + e.getMessage());
        }
    }

    // ---------------- 修订 ----------------

    @Test
    void reviseCopiesTheFrozenItemsIntoTheNextVersionAsADraft() {
        Long id = completeDataset();
        service.freeze(id);

        DatasetDetail fresh = service.revise(id);

        DatasetDetail old = service.detail(id);
        assertEquals(old.dataset().version() + 1, fresh.dataset().version());
        assertEquals(old.dataset().name(), fresh.dataset().name());
        assertEquals(old.dataset().kind(), fresh.dataset().kind());
        assertFalse(fresh.dataset().frozen());
        assertEquals(3, fresh.dataset().itemCount());
        assertEquals(3, rowsOf(fresh.dataset().id()).size());
        assertNotEquals(id, fresh.dataset().id(), "修订是新行，旧版本连同它的报告一起留着");
        assertTrue(fresh.dataset().note().contains("自 v1 修订"), fresh.dataset().note());
        assertTrue(old.dataset().frozen(), "旧版本原样留着");
    }

    @Test
    void reviseRefusesADraftBecauseThereIsNothingToKeep() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();

        assertTrue(conflict(() -> service.revise(id)).getMessage().contains("尚未冻结"));
    }

    // ---------------- 详情提醒 ----------------

    @Test
    void detailWarnsAboutSmallSampleSizeAndUnannotatedQuestions() {
        Long id = service.create(new DatasetRequest("集", "BENCHMARK", null)).dataset().id();
        // 只标了两题（quality 没标）→ 该题的指标分母会比别的题小，报告旁边得写清
        service.addItem(id, item(CaseGroups.VERBATIM_DUP, verbatimState(),
                Map.of(TriageQuestions.Q_LAYER, "global", TriageQuestions.Q_DUPLICATE, true)));
        service.addItem(id, item(CaseGroups.EMPTY_RECALL, emptyRecallState(),
                Map.of(TriageQuestions.Q_LAYER, "global", TriageQuestions.Q_DUPLICATE, false)));

        DatasetDetail detail = service.detail(id);

        assertTrue(detail.warnings().stream().anyMatch(w -> w.contains("偏少")), detail.warnings().toString());
        assertTrue(detail.warnings().stream().anyMatch(w -> w.contains(TriageQuestions.Q_QUALITY)),
                detail.warnings().toString());
        assertEquals(2, detail.coverage().stream()
                .filter(c -> c.questionId().equals(TriageQuestions.Q_LAYER)).findFirst().orElseThrow().annotated());
        assertEquals(0, detail.coverage().stream()
                .filter(c -> c.questionId().equals(TriageQuestions.Q_QUALITY)).findFirst().orElseThrow().annotated());
    }

    @Test
    void itemOfOneDatasetIsNotReachableThroughAnother() {
        Long a = service.create(new DatasetRequest("A", "BENCHMARK", null)).dataset().id();
        Long b = service.create(new DatasetRequest("B", "BENCHMARK", null)).dataset().id();
        Long itemId = service.addItem(a, normalItem()).item().id();

        DevMindException e = assertThrows(DevMindException.class, () -> service.item(b, itemId));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    // ---------------- 从决策记录收编 ----------------

    @Test
    void intakeCollectsUsableRecordsAndSaysWhyTheRestAreNot() {
        Long id = replayDataset();
        records.put(1L, record(1, emptyRecallState(), fullGold()));                       // 可用
        records.put(2L, record(2, state(GOOD_CONTENT, "1. 《X》\n无关"), null));           // 只分诊过，没裁决
        records.put(3L, recordWithOldQuestionSet(3));                                     // 旧题面测的记录
        records.put(4L, record(4, state(GOOD_CONTENT, "1. 《X》\n无关"),
                Map.of(TriageQuestions.Q_LAYER, "PROJECT")));                             // 人工动作落不上题面

        RecordsPreview preview = service.previewFromRecords(id, null, null, 10);

        assertEquals(4, preview.scanned());
        assertEquals(1, preview.collectable());
        assertEquals(1L, preview.skipReasons().get(RecordIntake.REASON_SNAPSHOT));
        assertEquals(1L, preview.skipReasons().get(RecordIntake.REASON_QUESTION_SET));
        assertEquals(1L, preview.skipReasons().get(RecordIntake.REASON_GOLD));
        assertEquals(0L, preview.skipReasons().get(RecordIntake.REASON_COLLECTED), "缺的原因也要出现");
        assertFalse(preview.skipReasons().containsKey(null),
                "可收编的不能以 null 为键混进原因表：null 键连 JSON 都序列化不了（预览整条 500）");
        assertFalse(preview.skipReasons().containsValue(null),
                "原因表是「原因 → 条数」，值不该是 null");
        assertEquals(3L, preview.skipReasons().values().stream().mapToLong(Long::longValue).sum(),
                "原因表的合计 = 被跳过的条数（可收编的那条不在里面）");
        assertEquals(1L, preview.caseGroups().get(CaseGroups.EMPTY_RECALL), "空召回记录按内容自动进对照组");
        assertFalse(preview.truncated());

        RecordsIntakeResult result = service.collectFromRecords(id, new RecordsIntakeRequest(null, null));

        assertEquals(1, result.added());
        assertEquals(3, result.skipped());
        DecisionDatasetItemEntity item = rowsOf(id).get(0);
        assertEquals(DecisionDatasetItemEntity.SOURCE_RECORD, item.getSource());
        assertEquals(1L, item.getOriginRecordId(), "溯源靠 origin_record_id，不是靠备注里的字");
        assertEquals(CaseGroups.EMPTY_RECALL, item.getCaseGroup());
        assertEquals(TriageQuestions.VERSION, item.getQuestionSetVersion());
        assertTrue(item.getNote().contains("回流自决策记录 #1"), item.getNote());
        assertEquals(1, result.dataset().dataset().itemCount());
    }

    @Test
    void intakeIsRepeatableWithoutDuplicatingSamples() {
        Long id = replayDataset();
        records.put(1L, record(1, emptyRecallState(), fullGold()));
        assertEquals(1, service.collectFromRecords(id, new RecordsIntakeRequest(null, null)).added());

        RecordsIntakeResult again = service.collectFromRecords(id, new RecordsIntakeRequest(null, null));

        assertEquals(0, again.added());
        assertEquals(1L, again.skipReasons().get(RecordIntake.REASON_COLLECTED));
        assertEquals(1, rowsOf(id).size(), "重复收编不该把样本翻倍——那会直接污染指标的分母");
    }

    @Test
    void intakePreviewSamplesShowWhatWouldBeCollectedFirst() {
        Long id = replayDataset();
        records.put(1L, record(1, state(GOOD_CONTENT, "1. 《X》\n无关"), null));
        records.put(2L, record(2, emptyRecallState(), fullGold()));

        RecordsPreview preview = service.previewFromRecords(id, null, null, 5);

        // 先给"马上要写进去的那条"（可核对自己要收的是不是它），再给一条被留下的
        assertEquals(2, preview.samples().size());
        assertEquals("COLLECT", preview.samples().get(0).outcome());
        assertEquals(2L, preview.samples().get(0).recordId());
        assertEquals("一些提案", preview.samples().get(0).title());
        assertEquals("SKIP", preview.samples().get(1).outcome());
        assertNotNull(preview.samples().get(1).reasonLabel());
    }

    @Test
    void intakeRefusesABenchmarkDataset() {
        Long id = service.create(new DatasetRequest("基准集", "BENCHMARK", null)).dataset().id();

        DevMindException e = badRequest(() ->
                service.collectFromRecords(id, new RecordsIntakeRequest(null, null)));
        assertTrue(e.getMessage().contains("REPLAY"), "要说清该建什么样的集：" + e.getMessage());
    }

    @Test
    void intakeRefusesAFrozenDataset() {
        Long id = completeDataset("REPLAY");
        service.freeze(id);

        assertTrue(conflict(() -> service.collectFromRecords(id, new RecordsIntakeRequest(null, null)))
                .getMessage().contains("已冻结"));
    }

    @Test
    void intakeRejectsAMalformedSinceInsteadOfSilentlyIgnoringIt() {
        Long id = replayDataset();

        // 悄悄当"不限时间"是最坏的选择：用户以为只收了上周的，实际把全部历史都收进来了
        DevMindException e = badRequest(() -> service.previewFromRecords(id, null, "2026/09/22", 10));
        assertTrue(e.getMessage().contains("yyyy-MM-dd"), e.getMessage());
    }

    @Test
    void intakeCanBeNarrowedByCapabilityAndDate() {
        Long id = replayDataset();
        records.put(1L, record(1, emptyRecallState(), fullGold(), "kb-proposal-triage", Instant.now()));
        records.put(2L, record(2, emptyRecallState(), fullGold(), "other-capability", Instant.now()));
        records.put(3L, record(3, emptyRecallState(), fullGold(), "kb-proposal-triage",
                Instant.now().minus(30, ChronoUnit.DAYS)));
        String lastWeek = LocalDate.now().minusDays(7).toString();

        assertEquals(2, service.previewFromRecords(id, "kb-proposal-triage", null, 10).scanned());
        assertEquals(2, service.previewFromRecords(id, null, lastWeek, 10).scanned());
        // 两个条件叠起来才是"最近一周的本能力记录"
        assertEquals(1, service.previewFromRecords(id, "kb-proposal-triage", lastWeek, 10).collectable());
    }

    // ---------------- 夹具 ----------------

    /** 三类对照组各一条的完整集 */
    private Long completeDataset() {
        return completeDataset("BENCHMARK");
    }

    private Long completeDataset(String kind) {
        Long id = service.create(new DatasetRequest("集", kind, "测试用")).dataset().id();
        service.addItem(id, item(CaseGroups.EMPTY_RECALL, emptyRecallState(), fullGold()));
        service.addItem(id, item(CaseGroups.VERBATIM_DUP, verbatimState(), fullGold()));
        service.addItem(id, item(CaseGroups.IRRELEVANT, irrelevantState(), fullGold()));
        return id;
    }

    private DatasetItemRequest normalItem() {
        return item(null, state(GOOD_CONTENT, "1. 《日志归档》\n" + GOOD_CONTENT), fullGold());
    }

    private static DatasetItemRequest item(String caseGroup, Map<String, Object> state, Map<String, Object> gold) {
        return new DatasetItemRequest(state, null, gold, caseGroup, null);
    }

    private static Map<String, Object> state(String content, String similarEntries) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("proposal_title", "一些提案");
        state.put(CaseGroups.CONTENT_KEY, content);
        state.put("project", "dev-mind");
        state.put(CaseGroups.SIMILAR_KEY, similarEntries);
        return state;
    }

    /** 三题的完整 gold（choice 给选项 key、score 给等级下标、noul 给是/否） */
    private static Map<String, Object> fullGold() {
        Map<String, Object> gold = new LinkedHashMap<>();
        gold.put(TriageQuestions.Q_LAYER, "project");
        gold.put(TriageQuestions.Q_DUPLICATE, false);
        gold.put(TriageQuestions.Q_QUALITY, 1);
        return gold;
    }

    private static Map<String, Object> emptyRecallState() {
        return state(GOOD_CONTENT, TriageQuestions.EMPTY_RECALL);
    }

    private static Map<String, Object> verbatimState() {
        return state(GOOD_CONTENT, "1. 《日志归档》\n" + GOOD_CONTENT);
    }

    private static Map<String, Object> irrelevantState() {
        return state(GOOD_CONTENT, "1. 《容器网络》\n" + OTHER_CONTENT);
    }

    private Long replayDataset() {
        return service.create(new DatasetRequest("回流集", DecisionDatasetEntity.KIND_REPLAY, null))
                .dataset().id();
    }

    private DecisionRecordEntity record(long id, Map<String, Object> state, Map<String, Object> gold) {
        return record(id, state, gold, "kb-proposal-triage", Instant.now());
    }

    /** 一条决策记录（CAP-55 的形状：三份快照 + 人工动作 + 裁决人） */
    private DecisionRecordEntity record(long id, Map<String, Object> state, Map<String, Object> gold,
                                        String capability, Instant createdAt) {
        DecisionRecordEntity row = new DecisionRecordEntity();
        row.setId(id);
        row.setCapability(capability);
        row.setSubjectId("kb-" + id);
        row.setStateJson(state == null ? null : json.write(state));
        row.setQuestionsJson(json.write(TriageQuestions.standard()));
        row.setGoldJson(gold == null ? null : json.write(gold));
        row.setHumanAction("adopt:project");
        row.setDecidedBy("tester");
        row.setCreatedAt(createdAt);
        return row;
    }

    /** 旧题面时代留下的记录（题面比今天多一题）——回流时不该混进来 */
    private DecisionRecordEntity recordWithOldQuestionSet(long id) {
        DecisionRecordEntity row = record(id, emptyRecallState(), fullGold());
        Map<String, Object> questions = new LinkedHashMap<>(TriageQuestions.standard());
        questions.put("legacy_question", Map.of("type", "noul"));
        row.setQuestionsJson(json.write(questions));
        return row;
    }

    /** 直接落库（绕过标注校验）：摸拟"被改过的库""从别处导入的集"，用于验冻结的第二道网 */
    private Long inject(Long datasetId, String caseGroup, Map<String, Object> state,
                        Map<String, Object> gold, String questionSetVersion) {
        DecisionDatasetItemEntity e = new DecisionDatasetItemEntity();
        e.setDatasetId(datasetId);
        e.setSource(DecisionDatasetItemEntity.SOURCE_MANUAL);
        e.setStateJson(json.write(state));
        e.setQuestionsJson(json.write(TriageQuestions.standard()));
        e.setGoldJson(json.write(gold));
        e.setCaseGroup(caseGroup);
        e.setQuestionSetVersion(questionSetVersion);
        e.setCreatedAt(Instant.now());
        e.setId(++itemSeq);
        items.put(e.getId(), e);
        return e.getId();
    }

    private List<DecisionDatasetItemEntity> rowsOf(Long datasetId) {
        return items.values().stream()
                .filter(r -> r.getDatasetId().equals(datasetId))
                .sorted(Comparator.comparing(DecisionDatasetItemEntity::getId))
                .toList();
    }

    private static DevMindException badRequest(Runnable action) {
        return failure(action, ErrorCode.BAD_REQUEST);
    }

    private static DevMindException conflict(Runnable action) {
        return failure(action, ErrorCode.CONFLICT);
    }

    private static DevMindException failure(Runnable action, ErrorCode expected) {
        DevMindException e = assertThrows(DevMindException.class, action::run);
        assertEquals(expected, e.getErrorCode(), e.getMessage());
        return e;
    }
}
