package com.devmind.decisionlab.eval;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.CheckpointService;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.config.DecisionLabProperties;
import com.devmind.decisionlab.dataset.DatasetService;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.eval.dto.EvalDetail;
import com.devmind.decisionlab.eval.dto.EvalTriggerRequest;
import com.devmind.decisionlab.eval.dto.EvalView;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-56 FR-03 评测编排：触发前的 fail-fast、异步执行的状态机、以及"报告有没有留下"这件事。
 *
 * <p>这里最要紧的一条：<b>"跑完了但没有报告"必须是一个可见的状态</b>，而不是"没指标=模型全错"。
 * 2026-09-22 那次退化之所以难发现，正是因为"模型答得整齐自信"看起来毫无异常；同理，
 * "脚本没打报告"若是显示成一片空白，读的人只会以为指标是 0。所以
 * {@link #aRunThatPrintedNoReportIsSuccessButVisiblyMissingOne} 与
 * {@link #reportIsKeptEvenWhenTheProcessExitsNonZero} 两条断言，测的其实是同一件事：
 * 证据在不在，要说得清。</p>
 *
 * <p>执行体（{@code AgentNodeStepRunner}）是 mock：这里测的是<b>服务端怎么用它的产出</b>
 * （状态、报告、日志分流），而不是节点上跑得对不对——那是 runner 与 python 侧的测试范围。</p>
 */
class EvalServiceTest {

    private static final long EVAL_ID = 7L;

    private final DecisionEvaluationRepository repo = mock(DecisionEvaluationRepository.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final CheckpointService checkpointService = mock(CheckpointService.class);
    private final AgentNodeRouter nodeRouter = mock(AgentNodeRouter.class);
    private final AgentNodeStepRunner stepRunner = mock(AgentNodeStepRunner.class);
    private final ExecutionLogHub hub = mock(ExecutionLogHub.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final DecisionLabProperties props = new DecisionLabProperties();
    private final Map<Long, DecisionEvaluationEntity> rows = new LinkedHashMap<>();

    @TempDir
    Path scriptsDir;

    private long seq;

    /**
     * 第一次落库那一刻的行快照。
     *
     * <p>触发接口是异步的（返回时执行线程可能已经在改行），所以断言"触发放下了什么"必须看
     * 落库当时的副本，而不是稍后去 rows 里读——那个对象可能已经是 RUNNING 了。</p>
     */
    private volatile DecisionEvaluationEntity firstSavedRow;

    private EvalService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(scriptsDir.resolve(LabScripts.EVAL_ENTRY), "print('eval')\n", StandardCharsets.UTF_8);
        props.setScriptsDir(scriptsDir.toString());
        when(identity.currentActor()).thenReturn("tester");
        when(nodeRouter.route(any(), any(), any())).thenReturn("node-1");
        when(repo.save(any(DecisionEvaluationEntity.class))).thenAnswer(inv -> {
            DecisionEvaluationEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++seq);
            }
            // 存副本、返回原对象：异步线程改的是副本，触发方拿到的视图不会被后台动过
            rows.put(e.getId(), copy(e));
            if (firstSavedRow == null) {
                firstSavedRow = copy(e);
            }
            return e;
        });
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(repo.countByStatusIn(any())).thenReturn(0L);
        service = new EvalService(repo, datasetService, checkpointService, props,
                new LabScripts(props), nodeRouter, stepRunner, hub, identity, mapper);
    }

    // ---------------- 触发前 fail-fast ----------------

    @Test
    void unfrozenDatasetIsRejectedAndNothingIsPersisted() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, false, 60));

        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(1L, 2L)));

        verify(repo, never()).save(any());
        verify(nodeRouter, never()).route(any(), any(), any());
    }

    @Test
    void datasetWithoutItemsIsRejected() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 0));

        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(1L, 2L)));
        verify(repo, never()).save(any());
    }

    @Test
    void checkpointWithoutSourcePathIsRejected() {
        DecisionCheckpointEntity ckpt = checkpoint(1L, "typed-decisions");
        ckpt.setSourcePath("  ");
        when(checkpointService.require(1L)).thenReturn(ckpt);
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(1L, 2L)));
    }

    @Test
    void baselineEqualToTheCheckpointItselfIsRejected() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        EvalTriggerRequest req = new EvalTriggerRequest(1L, 2L, 1L, null, null, null, null, null, null);
        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(req));
    }

    @Test
    void whitespaceInTheInterpreterPathIsRejectedBeforeRouting() {
        // runner 按首个 token 校验 execAllowlist：含空格的解释器路径会被节点打回，
        // 报出来的是"命令不在白名单"这种隔了一层的信息——所以在服务端先说清楚
        props.setPythonPath("C:/Program Files/Python312/python.exe");
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(1L, 2L)));
        verify(nodeRouter, never()).route(any(), any(), any());
    }

    @Test
    void missingScriptsDirectoryIsRejectedAtTriggerTime() throws IOException {
        Files.delete(scriptsDir.resolve(LabScripts.EVAL_ENTRY));
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        assertCode(ErrorCode.BAD_REQUEST, () -> service.trigger(request(1L, 2L)));
    }

    @Test
    void timeoutBoundsAreEnforced() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        assertCode(ErrorCode.BAD_REQUEST,
                () -> service.trigger(new EvalTriggerRequest(1L, 2L, null, null, null, null, null, 30L, null)));
        assertCode(ErrorCode.BAD_REQUEST,
                () -> service.trigger(new EvalTriggerRequest(1L, 2L, null, null, null, null, null, 86401L, null)));
    }

    @Test
    void concurrencyLimitIsEnforced() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));
        when(repo.countByStatusIn(any())).thenReturn(2L);   // 默认上限 2

        assertCode(ErrorCode.CONFLICT, () -> service.trigger(request(1L, 2L)));
        verify(repo, never()).save(any());
    }

    @Test
    void nodeThatCannotPullBundlesIsRejectedBeforePersisting() {
        // 协议 v14 才有 exec 帧的 bundle 块：在触发阶段就说清，而不是留一条 FAILED 让人去翻日志
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));
        doThrow(new DevMindException(ErrorCode.CONFLICT, "节点 node-1 的 runner 版本低于 14"))
                .when(nodeRouter).requireBundleCapable("node-1");

        DevMindException e = assertThrows(DevMindException.class, () -> service.trigger(request(1L, 2L)));
        assertEquals(ErrorCode.CONFLICT, e.getErrorCode());
        verify(repo, never()).save(any());
    }

    // ---------------- 触发落库 ----------------

    @Test
    void triggerPersistsTheRunSnapshotAndTheRenderedCommand() {
        when(checkpointService.require(1L)).thenReturn(checkpoint(1L, "typed-decisions"));
        when(checkpointService.require(3L)).thenReturn(checkpoint(3L, "english"));
        when(datasetService.requireDataset(2L)).thenReturn(dataset(2L, true, 60));

        EvalView view = service.trigger(new EvalTriggerRequest(1L, 2L, 3L, "node-9", "gpu,T4",
                "/opt/venv/bin/python", "/data/out/e1", 7200L, true));

        assertEquals("基准集 v2", view.datasetLabel());
        assertEquals("ckpt-1", view.checkpointLabel());
        assertEquals("ckpt-3", view.baseCheckpointLabel());
        assertEquals("node-1", view.nodeId());
        assertEquals("QUEUED", view.status());
        assertEquals(7200L, view.timeoutSeconds());
        assertEquals("TriageQuestions@1", view.questionSetVersion());
        assertEquals("排队中", view.reportLabel());

        // 用落库那一刻的快照断言（不读 rows：异步线程已经开始改它了）
        DecisionEvaluationEntity row = firstSavedRow;
        assertEquals("QUEUED", row.getStatus());
        assertEquals("/data/laya/typed-decisions", row.getCheckpointPath());
        assertEquals("typed-decisions", row.getServeSlot());
        assertEquals("/data/laya/english", row.getBaseCheckpointPath());
        assertEquals(60, row.getItemCount());
        assertEquals("/opt/venv/bin/python", firstToken(row.getCommandText()));
        assertTrue(row.getCommandText().contains("--slot typed-decisions"));
        assertTrue(row.getCommandText().contains("--baseline-slot english"));
        assertTrue(row.getCommandText().contains("--fit-temperature"));
        assertNull(row.getStartedAt(), "触发只排队：真正开跑时写 startedAt");
    }

    // ---------------- 异步执行 ----------------

    @Test
    void runPublishesTheReportStreamsItemsAndMarksSuccess() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        Map<String, Object> report = Map.of(
                "schemaVersion", 1,
                "metrics", Map.of("items", 60, "choice", Map.of("accuracy", 0.72)),
                "baselines", Map.of("random", 0.33, "majority", 0.46));
        stepEmits(new StepResult(true, 0, null),
                "[评测] 开始",
                LabMarkers.encode(LabMarkers.ITEM, Map.of("id", 1, "correct", false)),
                LabMarkers.encode(LabMarkers.REPORT, report));

        service.run(EVAL_ID);

        DecisionEvaluationEntity row = rows.get(EVAL_ID);
        assertEquals("SUCCESS", row.getStatus());
        assertEquals(DecisionEvaluationEntity.REPORT_OK, row.getReportStatus());
        assertEquals(0, row.getExitCode());
        assertNotNull(row.getStartedAt());
        assertNotNull(row.getFinishedAt());
        assertNotNull(row.getReportJson());
        // 人读日志里不能出现 base64 载荷，但那一行人读的进度要在
        assertTrue(row.getLogsText().contains("[评测] 开始"));
        assertTrue(row.getLogsText().contains("评测结果"));
        assertFalse(row.getLogsText().contains("DEVMIND_REPORT"),
                "marker 行不进人读日志：报告另有一等公民的落点（report_json）");
        assertNotNull(row.getHeadlineJson());
        verify(hub).publishEvent(eq(String.valueOf(EVAL_ID)), eq("item"), any());
        verify(hub).done(String.valueOf(EVAL_ID), "SUCCESS");
    }

    @Test
    void aRunThatPrintedNoReportIsSuccessButVisiblyMissingOne() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        stepEmits(new StepResult(true, 0, null), "[评测] 开始", "[评测] 结束");

        service.run(EVAL_ID);

        DecisionEvaluationEntity row = rows.get(EVAL_ID);
        assertEquals("SUCCESS", row.getStatus(), "退出码 0 就是跑完了——不能因为没打报告就说它失败");
        assertEquals(DecisionEvaluationEntity.REPORT_MISSING, row.getReportStatus());
        assertNull(row.getReportJson());
        assertEquals("跑完但无报告（脚本未打印 DEVMIND_REPORT 行）", EvalView.of(row, null).reportLabel());
    }

    @Test
    void malformedReportPayloadIsDistinguishableFromAMissingOne() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        stepEmits(new StepResult(true, 0, null), "DEVMIND_REPORT bm90LWd6aXA=");

        service.run(EVAL_ID);

        DecisionEvaluationEntity row = rows.get(EVAL_ID);
        assertEquals(DecisionEvaluationEntity.REPORT_MALFORMED, row.getReportStatus());
        assertTrue(EvalView.of(row, null).reportLabel().contains("无法解析"));
    }

    @Test
    void reportIsKeptEvenWhenTheProcessExitsNonZero() {
        // 报告可能已经打完、进程因收尾步骤（写盘/退出钩子）非零退出：把证据丢掉只会让人重跑一次
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        stepEmits(new StepResult(false, 1, "exit=1"),
                LabMarkers.encode(LabMarkers.REPORT, Map.of("schemaVersion", 1,
                        "metrics", Map.of("items", 60), "baselines", Map.of("random", 0.33))));

        service.run(EVAL_ID);

        DecisionEvaluationEntity row = rows.get(EVAL_ID);
        assertEquals("FAILED", row.getStatus());
        assertEquals(1, row.getExitCode());
        assertEquals("exit=1", row.getErrorSummary());
        assertEquals(DecisionEvaluationEntity.REPORT_OK, row.getReportStatus(), "失败的运行也可能留下有效的报告");
        assertNotNull(row.getReportJson());
        assertEquals("运行失败", EvalView.of(row, null).reportLabel());
    }

    @Test
    void reportThatMissesBaselinesIsIncompleteNotOk() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        stepEmits(new StepResult(true, 0, null),
                LabMarkers.encode(LabMarkers.REPORT, Map.of("schemaVersion", 1, "metrics", Map.of("items", 60))));

        service.run(EVAL_ID);

        // FR-03 硬要求：没有随机/多数类基线的数字无法解读，报告状态要把它标出来
        assertEquals(DecisionEvaluationEntity.REPORT_INCOMPLETE, rows.get(EVAL_ID).getReportStatus());
    }

    @Test
    void anExceptionFromTheStepRunnerBecomesAVisibleFailure() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.QUEUED, null);
        when(stepRunner.runStep(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("拉执行包失败: 404"));

        service.run(EVAL_ID);

        DecisionEvaluationEntity row = rows.get(EVAL_ID);
        assertEquals("FAILED", row.getStatus());
        assertEquals(-1, row.getExitCode());
        assertTrue(row.getErrorSummary().contains("拉执行包失败"));
        verify(hub).done(String.valueOf(EVAL_ID), "FAILED");
    }

    @Test
    void runOnAMissingRowIsANoOp() {
        service.run(404L);
        verify(hub, never()).done(any(), any());
    }

    // ---------------- 查询 ----------------

    @Test
    void detailExposesTheReportInSlicesAndTheCommandActuallyRun() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("metrics", Map.of("items", 60, "choice", Map.of("accuracy", 0.72)));
        report.put("baselines", Map.of("random", 0.33, "majority", 0.46));
        report.put("byCaseGroup", List.of(Map.of("caseGroup", "EMPTY_RECALL", "accuracy", 0.1)));
        report.put("perItem", List.of(Map.of("id", 3, "correct", false)));
        report.put("calibration", Map.of("mode", "heldout"));
        report.put("compare", Map.of("win", 12, "lose", 30, "tie", 18));
        seedRun(EVAL_ID, DecisionEvaluationEntity.SUCCESS, mapper.writeValueAsString(report));
        // 列表页头条是落库时摘好的（列表不为 20 行解析 20 份大报告）；这里补上那一列
        rows.get(EVAL_ID).setHeadlineJson(mapper.writeValueAsString(EvalReport.headline(report)));

        EvalDetail detail = service.detail(EVAL_ID);

        assertEquals(1, detail.byCaseGroup().size());
        assertEquals(1, detail.perItem().size());
        assertEquals("heldout", detail.calibration().get("mode"));
        assertEquals(12, ((Number) detail.compare().get("win")).intValue());
        assertNotNull(detail.commandText());
        assertEquals(0.72, ((Number) detail.view().headline().get("accuracy")).doubleValue(), 1e-9);
        assertEquals(0.33, ((Number) detail.view().headline().get("random")).doubleValue(), 1e-9);
        assertEquals(60, ((Number) detail.view().headline().get("items")).intValue());
    }

    @Test
    void detailOnARowWithoutReportGivesEmptySlicesNotNull() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.FAILED, null);
        EvalDetail detail = service.detail(EVAL_ID);
        assertEquals(Map.of(), detail.report());
        assertTrue(detail.byCaseGroup().isEmpty());
        assertTrue(detail.perItem().isEmpty());
        assertNull(detail.calibration());
        assertNull(detail.compare());
    }

    @Test
    void runningRowCannotBeDeleted() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.RUNNING, null);
        assertCode(ErrorCode.CONFLICT, () -> service.delete(EVAL_ID));
        verify(repo, never()).delete(any(DecisionEvaluationEntity.class));
    }

    @Test
    void logsAreServedFromTheStoredRowAndMissingRowIs404() {
        seedRun(EVAL_ID, DecisionEvaluationEntity.SUCCESS, null);
        rows.get(EVAL_ID).setLogsText("[评测] 开始\n");
        assertEquals("[评测] 开始\n", service.logs(EVAL_ID));
        assertCode(ErrorCode.NOT_FOUND, () -> service.logs(999L));
    }

    // ---------------- 夹子 ----------------

    private void seedRun(Long id, String status, String reportJson) {
        DecisionEvaluationEntity e = new DecisionEvaluationEntity();
        e.setId(id);
        e.setStatus(status);
        e.setDatasetId(2L);
        e.setDatasetName("基准集");
        e.setDatasetVersion(2);
        e.setQuestionSetVersion("TriageQuestions@1");
        e.setItemCount(60);
        e.setCheckpointId(1L);
        e.setCheckpointName("typed-decisions");
        e.setServeSlot("typed-decisions");
        e.setCheckpointPath("/data/laya/typed-decisions");
        e.setNodeId("node-1");
        e.setTimeoutSeconds(3600L);
        e.setCommandText("python \"$DEVMIND_LAB_SCRIPT\" --payload \"$DEVMIND_LAB_PAYLOAD\""
                + " --checkpoint '/data/laya/typed-decisions' --slot typed-decisions");
        e.setCreatedBy("tester");
        e.setCreatedAt(Instant.now());
        e.setReportJson(reportJson);
        e.setReportStatus(reportJson == null ? null : DecisionEvaluationEntity.REPORT_OK);
        rows.put(id, e);
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

    private EvalTriggerRequest request(Long checkpointId, Long datasetId) {
        return new EvalTriggerRequest(checkpointId, datasetId, null, null, null, null, null, null, null);
    }

    private static DecisionCheckpointEntity checkpoint(Long id, String slot) {
        DecisionCheckpointEntity e = new DecisionCheckpointEntity();
        e.setId(id);
        e.setName("ckpt-" + id);
        e.setKind(DecisionCheckpointEntity.KIND_BASE);
        e.setServeSlot(slot);
        e.setSourcePath("/data/laya/" + slot);
        return e;
    }

    private static DecisionDatasetEntity dataset(Long id, boolean frozen, int items) {
        DecisionDatasetEntity e = new DecisionDatasetEntity();
        e.setId(id);
        e.setName("基准集");
        e.setVersion(2);
        e.setFrozen(frozen);
        e.setItemCount(items);
        e.setQuestionSetVersion("TriageQuestions@1");
        return e;
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        DevMindException e = assertThrows(DevMindException.class, action::run);
        assertEquals(expected, e.getErrorCode(), e.getMessage());
    }

    private static String firstToken(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    /** 存副本用：异步执行会改行上的状态，触发方拿到的视图必须是当时那一刻的事实 */
    private static DecisionEvaluationEntity copy(DecisionEvaluationEntity e) {
        DecisionEvaluationEntity c = new DecisionEvaluationEntity();
        c.setId(e.getId());
        c.setDatasetId(e.getDatasetId());
        c.setDatasetName(e.getDatasetName());
        c.setDatasetVersion(e.getDatasetVersion());
        c.setQuestionSetVersion(e.getQuestionSetVersion());
        c.setCheckpointId(e.getCheckpointId());
        c.setCheckpointName(e.getCheckpointName());
        c.setServeSlot(e.getServeSlot());
        c.setCheckpointPath(e.getCheckpointPath());
        c.setBaseCheckpointId(e.getBaseCheckpointId());
        c.setBaseCheckpointName(e.getBaseCheckpointName());
        c.setBaseCheckpointPath(e.getBaseCheckpointPath());
        c.setNodeId(e.getNodeId());
        c.setTimeoutSeconds(e.getTimeoutSeconds());
        c.setStatus(e.getStatus());
        c.setCommandText(e.getCommandText());
        c.setItemCount(e.getItemCount());
        c.setReportStatus(e.getReportStatus());
        c.setHeadlineJson(e.getHeadlineJson());
        c.setReportJson(e.getReportJson());
        c.setLogsText(e.getLogsText());
        c.setExitCode(e.getExitCode());
        c.setErrorSummary(e.getErrorSummary());
        c.setCreatedBy(e.getCreatedBy());
        c.setCreatedAt(e.getCreatedAt());
        c.setStartedAt(e.getStartedAt());
        c.setFinishedAt(e.getFinishedAt());
        return c;
    }
}
