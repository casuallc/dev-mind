package com.devmind.decisionlab.finetune;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.CheckpointService;
import com.devmind.decisionlab.checkpoint.dto.CheckpointRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointView;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.config.DecisionLabProperties;
import com.devmind.decisionlab.dataset.DatasetService;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.eval.EvalService;
import com.devmind.decisionlab.eval.dto.EvalTriggerRequest;
import com.devmind.decisionlab.eval.dto.EvalView;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.finetune.dto.FinetuneDetail;
import com.devmind.decisionlab.finetune.dto.FinetuneTriggerRequest;
import com.devmind.decisionlab.finetune.dto.FinetuneView;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
import com.devmind.decisionlab.lab.LabConcurrency;
import com.devmind.decisionlab.lab.LabMarkers;
import com.devmind.decisionlab.lab.LabScripts;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.execution.ws.ExecutionLogHub;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-56 FR-05 微调编排：切分与超参的 fail-fast、训练跑完之后的<b>两件收尾事</b>
 * （登记产物 / 触发回评），以及它们失败时该留下什么。
 *
 * <p>这里最要紧的一条口径：<b>收尾失败不等于训练失败</b>。权重确实在节点上、指标确实算出来了，
 * 那是一份真实的产出；把它判成 FAILED，只会让人重跑一次整夜的训练。所以收尾的失败写进
 * {@code post_error}，与 SUCCESS 并列显示——{@link #registrationFailureIsAPostErrorNotAFailedRun}
 * 与 {@link #autoEvalFailureKeepsTheRegisteredCheckpoint} 两条断言测的就是这件事。</p>
 *
 * <p>另一条是<b>"回评集不能是训练集"</b>：训练集的一部分被拿去训练了，在它上面回评等于用练习题
 * 当考卷——而唯一的症状是"指标变好看了"。这条规则在这里被钉死，不靠人自觉。</p>
 */
class FinetuneServiceTest {

    private static final long FT_ID = 7L;
    private static final String SHA = "a".repeat(64);

    private final DecisionFinetuneRepository repo = mock(DecisionFinetuneRepository.class);
    private final DecisionEvaluationRepository evalRepo = mock(DecisionEvaluationRepository.class);
    private final DecisionDatasetItemRepository itemRepo = mock(DecisionDatasetItemRepository.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final EvalService evalService = mock(EvalService.class);
    private final AgentNodeRouter nodeRouter = mock(AgentNodeRouter.class);
    private final AgentNodeStepRunner stepRunner = mock(AgentNodeStepRunner.class);
    private final ExecutionLogHub hub = mock(ExecutionLogHub.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final DecisionLabProperties props = new DecisionLabProperties();
    private final Map<Long, DecisionFinetuneEntity> rows = new LinkedHashMap<>();

    @TempDir
    Path scriptsDir;

    private long seq;

    /**
     * 第一次落库那一刻的行快照。
     *
     * <p>触发接口是异步的（返回时执行线程可能已经在改行），所以断言"触发放下了什么"必须看
     * 落库当时的副本，而不是稍后去 rows 里读——那个对象可能已经是 RUNNING 了。</p>
     */
    private volatile DecisionFinetuneEntity firstSavedRow;

    private FinetuneService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(scriptsDir.resolve(LabScripts.FINETUNE_ENTRY), "print('train')\n",
                StandardCharsets.UTF_8);
        props.setScriptsDir(scriptsDir.toString());
        when(identity.currentActor()).thenReturn("tester");
        when(nodeRouter.route(any(), any(), any())).thenReturn("node-1");
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(items(5));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, "回流集", 1, 5, true));
        when(datasetService.requireDataset(3L)).thenReturn(dataset(3L, "基准集", 2, 60, true));
        when(checkpointService.require(1L)).thenReturn(base());
        when(repo.save(any(DecisionFinetuneEntity.class))).thenAnswer(inv -> {
            DecisionFinetuneEntity f = inv.getArgument(0);
            if (f.getId() == null) {
                f.setId(++seq);
            }
            rows.put(f.getId(), copy(f));
            if (firstSavedRow == null) {
                firstSavedRow = copy(f);
            }
            return f;
        });
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        LabConcurrency concurrency = new LabConcurrency(evalRepo, repo);
        service = new FinetuneService(repo, itemRepo, datasetService, checkpointService, evalService,
                props, new LabScripts(props), concurrency, nodeRouter, stepRunner, hub, identity, mapper);
    }

    // ---------------- 触发前 fail-fast ----------------

    @Test
    void missingRequiredIdsAreRejected() {
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, null, 1L)));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, null)));
        verify(repo, never()).save(any());
    }

    @Test
    void unfrozenTrainingSetIsRejected() {
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, "回流集", 1, 5, false));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L)));
        verify(repo, never()).save(any());
    }

    /**
     * 核心规则：回评集与训练集不能是同一份。
     *
     * <p>这条不靠人自觉——把回评集选成训练集是页面上最省事的一次点击，而它的唯一症状是
     * "指标看起来更好了"。所以拒绝，并把理由写成一句能读的话。</p>
     */
    @Test
    void evalDatasetMustDifferFromTheTrainingSet() {
        DevMindException e = assertThrows(DevMindException.class, () -> service.trigger(request(2L, 2L, 1L)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("练习题"), "理由要说给点这一下的人听：" + e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void trainingSetTooSmallToSplitIsRejected() {
        when(itemRepo.findByDatasetIdOrderByIdAsc(2L)).thenReturn(items(1));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L)));
    }

    @Test
    void baseWithoutSourcePathOrSlotIsRejected() {
        DecisionCheckpointEntity noPath = base();
        noPath.setSourcePath(null);
        when(checkpointService.require(1L)).thenReturn(noPath);
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L)));

        DecisionCheckpointEntity noSlot = base();
        noSlot.setServeSlot(null);
        when(checkpointService.require(1L)).thenReturn(noSlot);
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L)));
    }

    @Test
    void missingOutputDirectoryIsRejected() {
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(
                new FinetuneTriggerRequest(2L, 3L, 1L, null, null, null, "  ", null, null, null, null, null,
                        null, null, null)));
    }

    @Test
    void trainRatioOutOfRangeIsRejected() {
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L, r -> r.trainRatio = 1.0)));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L, r -> r.trainRatio = 0.0)));
    }

    /**
     * 超参是人在页面上填的，它们的问题必须在<b>路由节点之前</b>报出来。
     *
     * <p>顺序反了的话，一个 {@code epochs=0} 会一路走到"无可用节点"那里才炸——而那句话会
     * 把人引去查节点，真正的原因（超参填错）一个字都没出现。</p>
     */
    @Test
    void hyperparamProblemsAreReportedBeforeNodeRouting() {
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L, r -> r.epochs = 0)));
        verify(nodeRouter, never()).route(any(), any(), any());
    }

    @Test
    void whitespaceInTheInterpreterPathIsRejectedBeforeRouting() {
        // 带空格的解释器路径在节点上会被 execAllowlist 以首 token 打回（"C:/Program"），
        // 报出来的是"命令不在白名单"——与服务端真正能说清的原因隔了一层，所以在这里就拒
        assertCode(ErrorCode.BAD_REQUEST,
                () -> service.trigger(request(2L, 3L, 1L, r -> r.pythonPath = "C:/Program Files/Python/python.exe")));
        verify(nodeRouter, never()).route(any(), any(), any());
    }

    @Test
    void timeoutFloorIsHigherThanForEvaluation() {
        // 训练连加载权重都不止一分钟：把评测的默认值顺手抄过来，只会得到一条"超时失败"
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(2L, 3L, 1L, r -> r.timeoutSec = 60L)));
    }

    @Test
    void concurrencyGateCountsBothRunsAndFinetunes() {
        when(repo.countByStatusIn(any())).thenReturn(2L);   // 默认上限 2
        assertCode(ErrorCode.CONFLICT, () -> service.trigger(request(2L, 3L, 1L)));
        verify(repo, never()).save(any());
    }

    // ---------------- 触发落库 ----------------

    @Test
    void triggerPersistsTheSplitSnapshotAndTheRenderedCommand() {
        FinetuneView view = service.trigger(request(2L, 3L, 1L, r -> {
            r.epochs = 5;
            r.learningRate = 5e-5;
            r.batchSize = 16;
            r.trainSeed = 7L;
            r.splitSeed = 11L;
            r.launcher = "torchrun --nproc_per_node=2";
            r.nodeId = "node-9";
        }));

        assertEquals("回流集 v1", view.datasetLabel());
        assertEquals("基准集 v2", view.evalDatasetLabel());
        assertEquals("multilingual", view.serveSlot());
        assertEquals(4, view.trainCount());
        assertEquals(1, view.valCount(), "5 条样本按 0.8 切 = 4 训练 / 1 验证");
        assertEquals("QUEUED", view.status());
        assertEquals("排队中", view.reportLabel());
        assertNull(view.postLabel(), "还没跑完，收尾结论无从谈起");

        DecisionFinetuneEntity row = firstSavedRow;
        assertEquals(DecisionFinetuneEntity.QUEUED, row.getStatus());
        assertEquals(11L, row.getSplitSeed());
        assertEquals(4, row.getTrainCount());
        assertEquals(1, row.getValCount());
        assertEquals("/data/out/ft7", row.getOutputPath());
        assertEquals(5, row.getEpochs());
        assertEquals("torchrun --nproc_per_node=2", row.getLauncher());
        assertEquals("/opt/venv/bin/python", firstToken(row.getCommandText()));
        assertTrue(row.getCommandText().contains("--base-checkpoint '/data/laya/multilingual'"));
        assertTrue(row.getCommandText().contains("--slot multilingual"));
        assertTrue(row.getCommandText().contains("--out '/data/out/ft7'"));
        assertTrue(row.getCommandText().contains("--launcher 'torchrun --nproc_per_node=2'"));
        // 切分判据入库：报告的验证指标是在哪几条上算的，靠这一列说得清
        assertTrue(row.getValItemIdsJson().startsWith("[") && row.getValItemIdsJson().endsWith("]"));
        assertEquals(1, valIds(row).size());
        assertTrue(valIds(row).stream().allMatch(id -> id >= 1 && id <= 5));
        assertNull(row.getStartedAt(), "触发只排队：真正开跑时写 startedAt");
    }

    @Test
    void hyperparamsFallBackToPlatformDefaults() {
        service.trigger(request(2L, 3L, 1L));
        DecisionFinetuneEntity row = firstSavedRow;
        assertEquals(props.getDefaultEpochs(), row.getEpochs());
        assertEquals(props.getDefaultLearningRate(), row.getLearningRate());
        assertEquals(props.getDefaultBatchSize(), row.getBatchSize());
        assertEquals(props.getDefaultTrainSeed(), row.getTrainSeed());
        assertEquals(props.getDefaultSplitSeed(), row.getSplitSeed());
        assertEquals(props.getDefaultTrainRatio(), row.getTrainRatio());
        // 配置默认是空串（不是 null），所以入库就是"没填"本身；要点在于它不会被渲染成一次空参调用
        assertTrue(row.getLauncher() == null || row.getLauncher().isBlank());
        assertFalse(row.getCommandText().contains("--launcher"));
    }

    // ---------------- 异步执行 ----------------

    @Test
    void runStreamsTheReportAndMarksSuccess() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        stepEmits(new StepResult(true, 0, null), "[微调] 开始", "[微调] epoch 1/3",
                LabMarkers.encode(LabMarkers.REPORT, valReport()));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(DecisionFinetuneEntity.SUCCESS, row.getStatus());
        assertEquals(0, row.getExitCode());
        assertEquals(DecisionFinetuneEntity.REPORT_OK, row.getReportStatus());
        assertTrue(row.getLogsText().contains("[微调] 开始"));
        assertTrue(row.getLogsText().contains("[切分] 训练集"));
        assertFalse(row.getLogsText().contains(LabMarkers.REPORT),
                "marker 行不进人读日志：报告另有一等公民的落点（metrics_json）");
        verify(hub).done(String.valueOf(FT_ID), "SUCCESS");
    }

    @Test
    void aRunWithoutFingerprintStaysSuccessButSaysWhyThereIsNoArtifact() {
        // 训练确实跑完了；"没登记成产物"是收尾的事，不该把它判成 FAILED 让人重跑一次整夜
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        stepEmits(new StepResult(true, 0, null), LabMarkers.encode(LabMarkers.REPORT, valReport()));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(DecisionFinetuneEntity.SUCCESS, row.getStatus());
        assertEquals(DecisionFinetuneEntity.REPORT_OK, row.getReportStatus());
        assertNotNull(row.getPostError());
        assertTrue(row.getPostError().contains("指纹"), row.getPostError());
        assertNull(row.getCheckpointId());
        assertNull(row.getEvalId());
        assertTrue(FinetuneView.of(row, null).postLabel().contains("未登记产物"));
    }

    @Test
    void successfulRunRegistersTheArtifactAndTriggersTheEvaluation() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        when(checkpointService.create(any(CheckpointRequest.class)))
                .thenReturn(checkpointView(55L, "ft7-回流集"));
        when(evalService.trigger(any(EvalTriggerRequest.class))).thenReturn(evalView(99L));
        stepEmits(new StepResult(true, 0, null),
                LabMarkers.encode(LabMarkers.REPORT, valReport()),
                LabMarkers.encode(LabMarkers.FINGERPRINT,
                        Map.of("path", "/data/out/ft7/ckpt", "bytes", 123456L, "sha256", SHA)));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(DecisionFinetuneEntity.SUCCESS, row.getStatus());
        assertEquals(55L, row.getCheckpointId());
        assertEquals("ft7-回流集", row.getCheckpointName());
        assertEquals(99L, row.getEvalId());
        assertNull(row.getPostError());
        assertTrue(row.getLogsText().contains("[收尾] 已登记产物 #55"));
        assertTrue(row.getLogsText().contains("[回评] 已触发评测 #99"));

        ArgumentCaptor<CheckpointRequest> ckpt = ArgumentCaptor.forClass(CheckpointRequest.class);
        verify(checkpointService).create(ckpt.capture());
        assertEquals("ft7-回流集", ckpt.getValue().name());
        assertEquals(DecisionCheckpointEntity.KIND_FINETUNED, ckpt.getValue().kind());
        assertEquals("multilingual", ckpt.getValue().serveSlot());
        // 来源路径取脚本报回来的那一份（实际权重落在哪只有它知道），不取触发时人填的产出目录
        assertEquals("/data/out/ft7/ckpt", ckpt.getValue().sourcePath());
        assertEquals(SHA, ckpt.getValue().fingerprintSha256());
        assertEquals(123456L, ckpt.getValue().fingerprintBytes());

        ArgumentCaptor<EvalTriggerRequest> eval = ArgumentCaptor.forClass(EvalTriggerRequest.class);
        verify(evalService).trigger(eval.capture());
        assertEquals(55L, eval.getValue().checkpointId(), "回评的对象是刚登记出来的那一份");
        assertEquals(3L, eval.getValue().datasetId(), "回评用另一份集，不用训练集");
        assertEquals(1L, eval.getValue().baseCheckpointId(), "基线就是基座：要能逐题看胜负");
        assertEquals("/opt/venv/bin/python", eval.getValue().pythonPath(),
                "回评必须用训练时的解释器——否则同一个节点上『训练能跑、回评跑不起来』");
        assertEquals(Boolean.FALSE, eval.getValue().fitTemperature(),
                "校准已在验证切分上做过（held-out），在评测集上再拟合一次只会把 ECE 报得更漂亮");
    }

    /** 温度校准随产物走：它来自验证切分的 held-out 拟合，正是 FR-04 要的那个来源 */
    @Test
    void calibrationFromTheValidationSplitIsAttachedToTheArtifact() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        when(checkpointService.create(any(CheckpointRequest.class)))
                .thenReturn(checkpointView(55L, "ft7-回流集"));
        when(evalService.trigger(any(EvalTriggerRequest.class))).thenReturn(evalView(99L));
        Map<String, Object> report = valReport();
        report.put("calibration", Map.of("mode", "heldout", "temperature", Map.of("duplicate|3", 1.4)));
        stepEmits(new StepResult(true, 0, null),
                LabMarkers.encode(LabMarkers.REPORT, report),
                LabMarkers.encode(LabMarkers.FINGERPRINT,
                        Map.of("path", "/data/out/ft7/ckpt", "sha256", SHA)));

        service.run(FT_ID);

        ArgumentCaptor<String> calibration = ArgumentCaptor.forClass(String.class);
        verify(checkpointService).attachEvalResult(eq(55L), any(), calibration.capture());
        assertTrue(calibration.getValue().contains("heldout"));
    }

    @Test
    void registrationFailureIsAPostErrorNotAFailedRun() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        when(checkpointService.create(any(CheckpointRequest.class)))
                .thenThrow(new DevMindException(ErrorCode.CONFLICT, "已有同名产物「ft7-回流集」"));
        stepEmits(new StepResult(true, 0, null),
                LabMarkers.encode(LabMarkers.REPORT, valReport()),
                LabMarkers.encode(LabMarkers.FINGERPRINT, Map.of("path", "/data/out/ft7", "sha256", SHA)));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(DecisionFinetuneEntity.SUCCESS, row.getStatus());
        assertNull(row.getCheckpointId());
        assertTrue(row.getPostError().contains("已有同名产物"), row.getPostError());
        verify(evalService, never()).trigger(any());
        assertTrue(row.getLogsText().contains("[收尾]"), "收尾的那句话要进日志，页面上看得到");
    }

    @Test
    void autoEvalFailureKeepsTheRegisteredCheckpoint() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        when(checkpointService.create(any(CheckpointRequest.class)))
                .thenReturn(checkpointView(55L, "ft7-回流集"));
        when(evalService.trigger(any(EvalTriggerRequest.class)))
                .thenThrow(new DevMindException(ErrorCode.CONFLICT, "节点不在线: node-1"));
        stepEmits(new StepResult(true, 0, null),
                LabMarkers.encode(LabMarkers.REPORT, valReport()),
                LabMarkers.encode(LabMarkers.FINGERPRINT, Map.of("path", "/data/out/ft7", "sha256", SHA)));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(55L, row.getCheckpointId(), "产物已经登记了，回评失败不能把它一起抹掉");
        assertNull(row.getEvalId());
        assertTrue(row.getPostError().contains("节点不在线"), row.getPostError());
        assertTrue(FinetuneView.of(row, null).postLabel().contains("已登记"));
    }

    @Test
    void failedRunKeepsItsReportAndReportsNoPostStep() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        stepEmits(new StepResult(false, 1, "exit=1"),
                LabMarkers.encode(LabMarkers.REPORT, valReport()));

        service.run(FT_ID);

        DecisionFinetuneEntity row = rows.get(FT_ID);
        assertEquals(DecisionFinetuneEntity.FAILED, row.getStatus());
        assertEquals(DecisionFinetuneEntity.REPORT_OK, row.getReportStatus(), "失败的运行也可能留下有效的报告");
        assertTrue(row.getLogsText().contains("失败：exit=1"));
        assertNull(FinetuneView.of(row, null).postLabel(), "失败就没有收尾结论");
        verify(checkpointService, never()).create(any());
    }

    /** 打了报告行但解不出来（脚本版本不一致/载荷损坏）≠ 没打：前者要去查脚本，后者才是脚本没实现 */
    @Test
    void malformedReportIsDistinguishableFromAMissingOne() {
        seedRow(FT_ID, DecisionFinetuneEntity.QUEUED);
        stepEmits(new StepResult(true, 0, null), LabMarkers.REPORT + " bm90LWd6aXA=");

        service.run(FT_ID);

        assertEquals(DecisionFinetuneEntity.REPORT_MALFORMED, rows.get(FT_ID).getReportStatus());
    }

    @Test
    void runOnAMissingRowIsANoOp() {
        service.run(404L);
        verify(hub, never()).done(any(), any());
    }

    // ---------------- 查询 ----------------

    @Test
    void detailExposesTheSplitAndTheReportSlices() throws Exception {
        seedRow(FT_ID, DecisionFinetuneEntity.SUCCESS);
        DecisionFinetuneEntity row = rows.get(FT_ID);
        Map<String, Object> report = valReport();
        report.put("perItem", List.of(Map.of("id", 3, "correct", false)));
        report.put("calibration", Map.of("mode", "heldout"));
        report.put("train", Map.of("epochs", 3, "finalLoss", 0.42));
        row.setMetricsJson(mapper.writeValueAsString(report));
        row.setHeadlineJson(mapper.writeValueAsString(
                com.devmind.decisionlab.eval.EvalReport.headline(report)));
        row.setValItemIdsJson("[3]");

        FinetuneDetail detail = service.detail(FT_ID);

        assertEquals(List.of(3L), detail.valItemIds());
        assertEquals(1, detail.perItem().size());
        assertEquals("heldout", detail.calibration().get("mode"));
        assertEquals(3, ((Number) detail.train().get("epochs")).intValue());
        assertEquals(0.72, ((Number) detail.view().headline().get("accuracy")).doubleValue(), 1e-9);
        assertEquals(0.33, ((Number) detail.view().headline().get("random")).doubleValue(), 1e-9);
        assertNotNull(detail.commandText());
    }

    @Test
    void runningRowCannotBeDeleted() {
        seedRow(FT_ID, DecisionFinetuneEntity.RUNNING);
        assertCode(ErrorCode.CONFLICT, () -> service.delete(FT_ID));
        verify(repo, never()).delete(any(DecisionFinetuneEntity.class));
    }

    @Test
    void logsAreServedFromTheStoredRowAndMissingRowIs404() {
        seedRow(FT_ID, DecisionFinetuneEntity.SUCCESS);
        rows.get(FT_ID).setLogsText("[微调] 开始\n");
        assertEquals("[微调] 开始\n", service.logs(FT_ID));
        assertCode(ErrorCode.NOT_FOUND, () -> service.logs(999L));
    }

    // ---------------- 夹子 ----------------

    /** 请求的简写；{@code tweak} 用来在个别用例里改一两个字段（其余保持"全都合法"） */
    private FinetuneTriggerRequest request(Long datasetId, Long evalDatasetId, Long baseId) {
        return request(datasetId, evalDatasetId, baseId, r -> {
        });
    }

    private FinetuneTriggerRequest request(Long datasetId, Long evalDatasetId, Long baseId,
                                          Consumer<Req> tweak) {
        Req r = new Req();
        r.datasetId = datasetId;
        r.evalDatasetId = evalDatasetId;
        r.baseCheckpointId = baseId;
        r.pythonPath = "/opt/venv/bin/python";
        r.outputPath = "/data/out/ft7";
        tweak.accept(r);
        return new FinetuneTriggerRequest(r.datasetId, r.evalDatasetId, r.baseCheckpointId, r.nodeId,
                r.requiredLabels, r.pythonPath, r.outputPath, r.epochs, r.learningRate, r.batchSize,
                r.trainSeed, r.splitSeed, r.trainRatio, r.launcher, r.timeoutSec);
    }

    private static final class Req {
        Long datasetId;
        Long evalDatasetId;
        Long baseCheckpointId;
        String nodeId;
        String requiredLabels;
        String pythonPath;
        String outputPath;
        Integer epochs;
        Double learningRate;
        Integer batchSize;
        Long trainSeed;
        Long splitSeed;
        Double trainRatio;
        String launcher;
        Long timeoutSec;
    }

    private void seedRow(Long id, String status) {
        DecisionFinetuneEntity f = new DecisionFinetuneEntity();
        f.setId(id);
        f.setStatus(status);
        f.setDatasetId(2L);
        f.setDatasetName("回流集");
        f.setDatasetVersion(1);
        f.setQuestionSetVersion("TriageQuestions@1");
        f.setItemCount(5);
        f.setTrainCount(4);
        f.setValCount(1);
        f.setSplitSeed(42L);
        f.setTrainRatio(0.8);
        f.setValItemIdsJson("[3]");
        f.setEvalDatasetId(3L);
        f.setEvalDatasetName("基准集");
        f.setEvalDatasetVersion(2);
        f.setBaseCheckpointId(1L);
        f.setBaseCheckpointName("multilingual");
        f.setBaseServeSlot("multilingual");
        f.setBaseCheckpointPath("/data/laya/multilingual");
        f.setServeSlot("multilingual");
        f.setOutputPath("/data/out/ft7");
        f.setEpochs(3);
        f.setLearningRate(1e-4);
        f.setBatchSize(8);
        f.setTrainSeed(42L);
        f.setPythonPath("/opt/venv/bin/python");
        f.setNodeId("node-1");
        f.setTimeoutSeconds(3600L);
        f.setCommandText("python \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\""
                + " --base-checkpoint '/data/laya/multilingual' --slot multilingual --out '/data/out/ft7'");
        f.setCreatedBy("tester");
        f.setCreatedAt(Instant.now());
        rows.put(id, f);
    }

    /** 让执行体像真 runner 那样把日志行吐给 sink，然后返回给定结果 */
    private void stepEmits(StepResult result, String... lines) {
        when(stepRunner.runStep(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<String> sink = inv.getArgument(9);
                    for (String line : lines) {
                        sink.accept(line);
                    }
                    return result;
                });
    }

    private static Map<String, Object> valReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("metrics", Map.of("items", 5, "choice", Map.of("accuracy", 0.72)));
        report.put("baselines", Map.of("random", 0.33, "majority", 0.46));
        return report;
    }

    private static DecisionDatasetEntity dataset(Long id, String name, int version, int itemCount,
                                                boolean frozen) {
        DecisionDatasetEntity d = new DecisionDatasetEntity();
        d.setId(id);
        d.setName(name);
        d.setKind(DecisionDatasetEntity.KIND_BENCHMARK);
        d.setVersion(version);
        d.setItemCount(itemCount);
        d.setFrozen(frozen);
        d.setQuestionSetVersion("TriageQuestions@1");
        return d;
    }

    private static DecisionCheckpointEntity base() {
        DecisionCheckpointEntity c = new DecisionCheckpointEntity();
        c.setId(1L);
        c.setName("multilingual");
        c.setServeSlot("multilingual");
        c.setKind(DecisionCheckpointEntity.KIND_BASE);
        c.setSourcePath("/data/laya/multilingual");
        return c;
    }

    private static List<DecisionDatasetItemEntity> items(int n) {
        return java.util.stream.LongStream.rangeClosed(1, n).mapToObj(id -> {
            DecisionDatasetItemEntity row = new DecisionDatasetItemEntity();
            row.setId(id);
            row.setDatasetId(2L);
            return row;
        }).toList();
    }

    private static CheckpointView checkpointView(Long id, String name) {
        return new CheckpointView(id, name, "multilingual", DecisionCheckpointEntity.KIND_FINETUNED,
                "微调产物", "/data/out/ft7/ckpt", "node-1", "/data/out/ft7/ckpt", 123L, SHA,
                false, null, null, null, true, true, null, null, "tester", Instant.now(), null);
    }

    private static EvalView evalView(Long id) {
        DecisionEvaluationEntity e = new DecisionEvaluationEntity();
        e.setId(id);
        e.setCheckpointId(55L);
        e.setDatasetId(3L);
        e.setDatasetName("基准集");
        e.setStatus(DecisionEvaluationEntity.QUEUED);
        return EvalView.of(e, Map.of());
    }

    private List<Long> valIds(DecisionFinetuneEntity row) {
        return service.detail(row.getId()).valItemIds();
    }

    private static DecisionFinetuneEntity copy(DecisionFinetuneEntity src) {
        DecisionFinetuneEntity c = new DecisionFinetuneEntity();
        c.setId(src.getId());
        c.setStatus(src.getStatus());
        c.setDatasetId(src.getDatasetId());
        c.setDatasetName(src.getDatasetName());
        c.setDatasetVersion(src.getDatasetVersion());
        c.setQuestionSetVersion(src.getQuestionSetVersion());
        c.setItemCount(src.getItemCount());
        c.setTrainCount(src.getTrainCount());
        c.setValCount(src.getValCount());
        c.setSplitSeed(src.getSplitSeed());
        c.setTrainRatio(src.getTrainRatio());
        c.setValItemIdsJson(src.getValItemIdsJson());
        c.setEvalDatasetId(src.getEvalDatasetId());
        c.setEvalDatasetName(src.getEvalDatasetName());
        c.setEvalDatasetVersion(src.getEvalDatasetVersion());
        c.setBaseCheckpointId(src.getBaseCheckpointId());
        c.setBaseCheckpointName(src.getBaseCheckpointName());
        c.setBaseServeSlot(src.getBaseServeSlot());
        c.setBaseCheckpointPath(src.getBaseCheckpointPath());
        c.setServeSlot(src.getServeSlot());
        c.setOutputPath(src.getOutputPath());
        c.setEpochs(src.getEpochs());
        c.setLearningRate(src.getLearningRate());
        c.setBatchSize(src.getBatchSize());
        c.setTrainSeed(src.getTrainSeed());
        c.setLauncher(src.getLauncher());
        c.setPythonPath(src.getPythonPath());
        c.setNodeId(src.getNodeId());
        c.setTimeoutSeconds(src.getTimeoutSeconds());
        c.setCommandText(src.getCommandText());
        c.setReportStatus(src.getReportStatus());
        c.setHeadlineJson(src.getHeadlineJson());
        c.setMetricsJson(src.getMetricsJson());
        c.setFingerprintJson(src.getFingerprintJson());
        c.setCheckpointId(src.getCheckpointId());
        c.setCheckpointName(src.getCheckpointName());
        c.setEvalId(src.getEvalId());
        c.setPostError(src.getPostError());
        c.setLogsText(src.getLogsText());
        c.setExitCode(src.getExitCode());
        c.setErrorSummary(src.getErrorSummary());
        c.setCreatedBy(src.getCreatedBy());
        c.setCreatedAt(src.getCreatedAt());
        c.setStartedAt(src.getStartedAt());
        c.setFinishedAt(src.getFinishedAt());
        return c;
    }

    private static String firstToken(String command) {
        return command.split(" ", 2)[0];
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        DevMindException e = assertThrows(DevMindException.class, action);
        assertEquals(expected, e.getErrorCode(), e.getMessage());
    }
}
