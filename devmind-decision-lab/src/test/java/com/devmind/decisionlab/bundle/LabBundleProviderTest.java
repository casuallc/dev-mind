package com.devmind.decisionlab.bundle;

import com.devmind.common.decision.LabBundle;
import com.devmind.common.decision.LabBundles;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.config.DecisionLabProperties;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.lab.LabScripts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-56 执行包供给：节点凭 {@code kind+id} 拉包时现构建的那一份，内容必须与"这次要跑的事"一致。
 *
 * <p>三条断言是这份实现的全部要点：
 * <ul>
 *   <li><b>任务卷宗取自评测行自己的快照列</b>（{@link #payloadCarriesTheRunSnapshotNotAReLookup}）——
 *       报告是照着行写的，包里若回查到别的值，事后没人说得清这次到底测了什么；</li>
 *   <li><b>只服务未结束的运行</b>（{@link #finishedRunIsRefused}）——旧帧重放拉一次包，
 *       会把报告写回一条已经终态的行；</li>
 *   <li><b>坏数据上抛而不是跳过</b>（{@link #corruptItemFailsLoudly}）——丢一条样本会让指标的
 *       分母悄悄变小，那比不跑更糟。</li>
 * </ul>
 */
class LabBundleProviderTest {

    private static final String QUESTIONS = """
            {"duplicate":{"type":"choice","criteria":{"重复":"与已有条目重复","采纳":"值得采纳"}}}""";

    private final DecisionEvaluationRepository evalRepo = mock(DecisionEvaluationRepository.class);
    private final DecisionDatasetItemRepository itemRepo = mock(DecisionDatasetItemRepository.class);

    @TempDir
    Path scriptsDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private LabBundleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(scriptsDir.resolve(LabScripts.EVAL_ENTRY), "print('eval')\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("_rl_common.py"), "MARKER='DEVMIND_REPORT'\n",
                StandardCharsets.UTF_8);
        DecisionLabProperties props = new DecisionLabProperties();
        props.setScriptsDir(scriptsDir.toString());
        provider = new LabBundleProvider(evalRepo, itemRepo, new LabScripts(props), new LabPayload(mapper));
    }

    // ---------------- 正常路径 ----------------

    @Test
    void buildsAZipWithManifestScriptsAndTheBooksData() throws IOException {
        givenRun(7L, DecisionEvaluationEntity.QUEUED);
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}")));

        LabBundle bundle = provider.labBundle("evaluation", "7").orElseThrow();

        assertEquals("laya-eval-7.zip", bundle.fileName());
        LabBundles.Manifest manifest = LabBundles.readManifest(bundle.zip());
        assertEquals(LabScripts.EVAL_ENTRY, manifest.entry());
        assertEquals(LabBundles.DEFAULT_PAYLOAD_NAME, manifest.payloadName());
        assertNotNull(LabBundles.readEntry(bundle.zip(), "_rl_common.py"),
                "共用模块必须一起进包——少一个在节点上就是 ImportError，看起来像脚本写错了");

        JsonNode payload = payload(bundle);
        assertEquals(1, payload.path("schemaVersion").asInt());
        assertEquals("evaluation", payload.path("kind").asText());
        assertEquals(7, payload.path("taskId").asInt());
        JsonNode item = payload.path("items").get(0);
        // 分布由服务端按题面口径摊好（与训练目标同一份换算），脚本直接用，不再自己实现一遍：
        // 形状是「题 id → 选项分布」，与 decision_records 的训练 gold 完全一致
        assertEquals(1.0, item.path("goldDistribution").path("duplicate").path("重复").asDouble(), 1e-9);
        assertEquals(0.0, item.path("goldDistribution").path("duplicate").path("采纳").asDouble(), 1e-9);
        assertEquals("重复", item.path("gold").path("duplicate").asText());
        assertEquals(1, item.path("scorableCount").asInt());
        assertEquals(1, payload.path("caseGroupCounts").path("NORMAL").asInt());
    }

    @Test
    void payloadCarriesTheRunSnapshotNotAReLookup() throws IOException {
        // 行里记的是什么就发什么：checkpoint 行事后被删/被改都不该让这一次执行换一份数据
        DecisionEvaluationEntity run = givenRun(9L, DecisionEvaluationEntity.RUNNING);
        run.setCheckpointName("typed-v3");
        run.setServeSlot("typed-decisions");
        run.setCheckpointPath("/data/laya/typed-v3");
        run.setBaseCheckpointId(2L);
        run.setBaseCheckpointName("english");
        run.setBaseCheckpointPath("/data/laya/english");
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}")));

        JsonNode payload = payload(provider.labBundle("evaluation", "9").orElseThrow());

        assertEquals("基准集", payload.path("dataset").path("name").asText());
        assertEquals(2, payload.path("dataset").path("version").asInt());
        assertEquals("TriageQuestions@1", payload.path("dataset").path("questionSetVersion").asText());
        assertEquals("/data/laya/typed-v3", payload.path("checkpoint").path("path").asText());
        assertEquals("typed-decisions", payload.path("checkpoint").path("serveSlot").asText());
        assertEquals("/data/laya/english", payload.path("baseCheckpoint").path("path").asText());
    }

    @Test
    void partiallyAnnotatedSampleBecomesAVisibleWarning() throws IOException {
        // gold 只标了一半是允许的（冻结时只提醒不阻断），但报告里必须说得出"指标只覆盖了几题"
        givenRun(11L, DecisionEvaluationEntity.QUEUED);
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}"),
                item(4L, "NORMAL", QUESTIONS, "{}")));

        JsonNode payload = payload(provider.labBundle("evaluation", "11").orElseThrow());

        assertEquals(1, payload.path("warnings").size());
        assertTrue(payload.path("warnings").get(0).asText().contains("#4"),
                "要点名到条目，否则没法回去补标");
    }

    // ---------------- 拒绝路径 ----------------

    @Test
    void finishedRunIsRefused() {
        givenRun(12L, DecisionEvaluationEntity.SUCCESS);
        assertTrue(provider.labBundle("evaluation", "12").isEmpty());
        givenRun(13L, DecisionEvaluationEntity.FAILED);
        assertTrue(provider.labBundle("evaluation", "13").isEmpty());
    }

    @Test
    void unknownKindAndNotYetImplementedFinetuneAreRefused() {
        assertTrue(provider.labBundle("whatever", "1").isEmpty());
        // 微调执行包在 Phase 7 接上；在那之前必须 404 而不是给一个空包（空包会静默跑出"成功"）
        assertTrue(provider.labBundle("finetune", "1").isEmpty());
    }

    @Test
    void unknownOrNonNumericIdIsRefused() {
        assertTrue(provider.labBundle("evaluation", "abc").isEmpty());
        assertTrue(provider.labBundle("evaluation", "404").isEmpty());
    }

    @Test
    void emptyDatasetIsRefused() {
        givenRun(14L, DecisionEvaluationEntity.QUEUED);
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        assertTrue(provider.labBundle("evaluation", "14").isEmpty());
    }

    @Test
    void corruptItemFailsLoudly() {
        givenRun(15L, DecisionEvaluationEntity.QUEUED);
        DecisionDatasetItemEntity broken = item(5L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}");
        broken.setStateJson("{\"broken\": ");   // 半个 JSON：库被人手改过之类
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(broken));

        DevMindException e = assertThrows(DevMindException.class,
                () -> provider.labBundle("evaluation", "15"));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("#5"), "错误里要指明是哪一条，否则要去翻整集的 JSON");
    }

    @Test
    void missingScriptsDirectoryFailsLoudly() {
        DecisionLabProperties props = new DecisionLabProperties();
        props.setScriptsDir(scriptsDir.resolve("nope").toString());
        provider = new LabBundleProvider(evalRepo, itemRepo, new LabScripts(props), new LabPayload(mapper));
        givenRun(16L, DecisionEvaluationEntity.QUEUED);
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}")));

        // 拉包失败 = 该步骤失败（不降级跑一个没有脚本的步骤）；错误信息要指向配置项
        DevMindException e = assertThrows(DevMindException.class,
                () -> provider.labBundle("evaluation", "16"));
        assertTrue(e.getMessage().contains("scripts-dir"));
    }

    // ---------------- 夹子 ----------------

    private DecisionEvaluationEntity givenRun(Long id, String status) {
        DecisionEvaluationEntity e = new DecisionEvaluationEntity();
        e.setId(id);
        e.setStatus(status);
        e.setDatasetId(7L);
        e.setDatasetName("基准集");
        e.setDatasetVersion(2);
        e.setQuestionSetVersion("TriageQuestions@1");
        e.setItemCount(2);
        e.setCheckpointId(1L);
        e.setCheckpointName("typed-decisions");
        e.setServeSlot("typed-decisions");
        e.setCheckpointPath("/data/laya/typed-decisions");
        when(evalRepo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    private static DecisionDatasetItemEntity item(Long id, String caseGroup, String questions, String gold) {
        DecisionDatasetItemEntity row = new DecisionDatasetItemEntity();
        row.setId(id);
        row.setDatasetId(7L);
        row.setCaseGroup(caseGroup);
        row.setSource(DecisionDatasetItemEntity.SOURCE_MANUAL);
        row.setQuestionSetVersion("TriageQuestions@1");
        row.setStateJson("{\"retrieved\":[{\"id\":\"kb-1\",\"title\":\"重复条目\"}]}");
        row.setQuestionsJson(questions);
        row.setGoldJson(gold);
        return row;
    }

    private JsonNode payload(LabBundle bundle) throws IOException {
        return mapper.readTree(LabBundles.readEntry(bundle.zip(), LabBundles.DEFAULT_PAYLOAD_NAME));
    }
}
