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
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
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
    private final DecisionFinetuneRepository finetuneRepo = mock(DecisionFinetuneRepository.class);
    private final DecisionDatasetItemRepository itemRepo = mock(DecisionDatasetItemRepository.class);

    @TempDir
    Path scriptsDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private LabBundleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(scriptsDir.resolve(LabScripts.EVAL_ENTRY), "print('eval')\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve(LabScripts.FINETUNE_ENTRY), "print('train')\n", StandardCharsets.UTF_8);
        Files.writeString(scriptsDir.resolve("_rl_common.py"), "MARKER='DEVMIND_REPORT'\n",
                StandardCharsets.UTF_8);
        DecisionLabProperties props = new DecisionLabProperties();
        props.setScriptsDir(scriptsDir.toString());
        provider = new LabBundleProvider(evalRepo, finetuneRepo, itemRepo, new LabScripts(props),
                new LabPayload(mapper), mapper);
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
    void unknownKindIsRefused() {
        // 不认识的 kind 必须 404 而不是给一个空包（空包会静默跑出"成功"）
        assertTrue(provider.labBundle("whatever", "1").isEmpty());
    }

    // ---------------- 微调包（FR-05） ----------------

    /**
     * 微调包的核心是<b>切分标记</b>：脚本按 {@code split} 字段分流，而不是自己按 seed 再切一次。
     * 自己切的话，"报告上那个验证指标是在哪几条上算的"就没人答得上来了。
     */
    @Test
    void finetuneBundleMarksEachItemWithItsSplit() throws IOException {
        givenFinetune(7L, DecisionFinetuneEntity.QUEUED, "[3]");
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}"),
                item(4L, "EMPTY_RECALL", QUESTIONS, "{\"duplicate\":\"采纳\"}"),
                item(5L, "NORMAL", QUESTIONS, "{\"duplicate\":\"采纳\"}")));

        LabBundle bundle = provider.labBundle("finetune", "7").orElseThrow();

        assertEquals("laya-train-7.zip", bundle.fileName());
        assertEquals(LabScripts.FINETUNE_ENTRY, LabBundles.readManifest(bundle.zip()).entry(),
                "微调包要装训练脚本，而不是评测脚本");

        JsonNode payload = payload(bundle);
        assertEquals("finetune", payload.path("kind").asText());
        assertEquals("VAL", splitOf(payload, 3L));
        assertEquals("TRAIN", splitOf(payload, 4L));
        assertEquals("TRAIN", splitOf(payload, 5L));
        assertEquals(2, payload.path("split").path("train").asInt());
        assertEquals(1, payload.path("split").path("val").asInt());
        assertEquals(17, payload.path("split").path("splitSeed").asInt());
        assertEquals("/data/out/ft7", payload.path("outputPath").asText());
        assertEquals("multilingual", payload.path("serveSlot").asText());
        assertEquals(3, payload.path("hyper").path("epochs").asInt());
        assertEquals("/data/laya/multilingual", payload.path("baseCheckpoint").path("path").asText());
    }

    @Test
    void finetuneWithoutValidationSplitIsRefused() {
        // 没有验证切分 = 跑完只有训练损失，"学得怎么样"无从说起：宁可不跑
        givenFinetune(8L, DecisionFinetuneEntity.QUEUED, "[]");
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}")));
        assertTrue(provider.labBundle("finetune", "8").isEmpty());

        givenFinetune(9L, DecisionFinetuneEntity.QUEUED, null);
        assertTrue(provider.labBundle("finetune", "9").isEmpty());
    }

    @Test
    void valSplitPointingAtMissingItemsIsWarnedAboutNotHidden() throws IOException {
        // 切分记录与样本对不上（样本被删过）：报告的分母会少，但这件事必须说出来
        givenFinetune(10L, DecisionFinetuneEntity.QUEUED, "[3, 999]");
        when(itemRepo.findByDatasetIdOrderByIdAsc(anyLong())).thenReturn(List.of(
                item(3L, "NORMAL", QUESTIONS, "{\"duplicate\":\"重复\"}"),
                item(4L, "NORMAL", QUESTIONS, "{\"duplicate\":\"采纳\"}")));

        JsonNode payload = payload(provider.labBundle("finetune", "10").orElseThrow());

        assertEquals(1, payload.path("warnings").size());
        assertTrue(payload.path("warnings").get(0).asText().contains("1 条"),
                "要说清少了几条，而不是静默按现有的算：" + payload.path("warnings"));
        assertEquals(1, payload.path("split").path("val").asInt(), "实际进验证集的只有那条存在的");
    }

    @Test
    void finishedFinetuneIsRefused() {
        givenFinetune(11L, DecisionFinetuneEntity.SUCCESS, "[3]");
        assertTrue(provider.labBundle("finetune", "11").isEmpty());
    }

    private static String splitOf(JsonNode payload, long itemId) {
        for (JsonNode item : payload.path("items")) {
            if (item.path("id").asLong() == itemId) {
                return item.path("split").asText();
            }
        }
        throw new AssertionError("payload 里没有样本 #" + itemId);
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
        provider = new LabBundleProvider(evalRepo, finetuneRepo, itemRepo, new LabScripts(props),
                new LabPayload(mapper), mapper);
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

    private DecisionFinetuneEntity givenFinetune(Long id, String status, String valItemIdsJson) {
        DecisionFinetuneEntity f = new DecisionFinetuneEntity();
        f.setId(id);
        f.setStatus(status);
        f.setDatasetId(7L);
        f.setDatasetName("回流集");
        f.setDatasetVersion(1);
        f.setQuestionSetVersion("TriageQuestions@1");
        f.setItemCount(3);
        f.setTrainCount(2);
        f.setValCount(1);
        f.setSplitSeed(17L);
        f.setTrainRatio(0.66);
        f.setValItemIdsJson(valItemIdsJson);
        f.setEvalDatasetId(21L);
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
        f.setLauncher("");
        when(finetuneRepo.findById(id)).thenReturn(Optional.of(f));
        return f;
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
