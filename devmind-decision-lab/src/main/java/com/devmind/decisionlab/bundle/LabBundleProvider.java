package com.devmind.decisionlab.bundle;

import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.decision.LabBundle;
import com.devmind.common.decision.LabBundles;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
import com.devmind.decisionlab.lab.LabScripts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 执行包供给（{@link DecisionLabBundleProvider} 的实现）：节点拉包时<b>现构建</b>
 * 一份「脚本 + 本次任务的数据」的 zip。
 *
 * <p><b>任务卷宗来自评测行自己的快照列，不去回查 checkpoint 行</b>：行里已经记下了
 * 本次跑的 checkpoint 名/槽位/路径与基线。回查的话，一个"跑完就被删掉的登记"或"改过来源的登记"
 * 会让执行包与评测行说的不是同一件事——而报告是照着行写的，两者一旦不一致，
 * 事后没人说得清这次到底测了什么。行是当时的事实，行里有的就只认行里的。</p>
 *
 * <p><b>只服务 QUEUED/RUNNING</b>：已结束的运行不再供包。这挡住的是"旧帧被重放"——
 * 一次跑完很久之后再拉一次包，会拿到一份"和当时一样的数据但已经不是当时那次执行"的东西，
 * 没有任何用处，而它一旦被跑起来，报告会写回一条已经终态的行。</p>
 */
@Component
public class LabBundleProvider implements DecisionLabBundleProvider {

    private static final Logger log = LoggerFactory.getLogger(LabBundleProvider.class);

    /** 样本上的切分标记（脚本按它分流，不自己再切一次） */
    static final String SPLIT_TRAIN = "TRAIN";
    static final String SPLIT_VAL = "VAL";

    private final DecisionEvaluationRepository evalRepo;
    private final DecisionFinetuneRepository finetuneRepo;
    private final DecisionDatasetItemRepository itemRepo;
    private final LabScripts scripts;
    private final LabPayload payload;
    private final ObjectMapper mapper;

    public LabBundleProvider(DecisionEvaluationRepository evalRepo,
                             DecisionFinetuneRepository finetuneRepo,
                             DecisionDatasetItemRepository itemRepo, LabScripts scripts,
                             LabPayload payload, ObjectMapper mapper) {
        this.evalRepo = evalRepo;
        this.finetuneRepo = finetuneRepo;
        this.itemRepo = itemRepo;
        this.scripts = scripts;
        this.payload = payload;
        this.mapper = mapper;
    }

    @Override
    public Optional<LabBundle> labBundle(String kind, String id) {
        if (KIND_EVALUATION.equals(kind)) {
            return evaluation(id);
        }
        if (KIND_FINETUNE.equals(kind)) {
            return finetune(id);
        }
        return Optional.empty();
    }

    private Optional<LabBundle> evaluation(String id) {
        Long evalId = parseId(id);
        if (evalId == null) {
            return Optional.empty();
        }
        DecisionEvaluationEntity e = evalRepo.findById(evalId).orElse(null);
        if (e == null) {
            return Optional.empty();
        }
        if (!DecisionEvaluationEntity.QUEUED.equals(e.getStatus())
                && !DecisionEvaluationEntity.RUNNING.equals(e.getStatus())) {
            log.info("评测 {} 状态 {} 已不需要执行，拒绝供包", evalId, e.getStatus());
            return Optional.empty();
        }
        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(e.getDatasetId());
        if (rows.isEmpty()) {
            log.warn("评测 {} 的数据集 {} 已无样本，无法构建执行包", evalId, e.getDatasetId());
            return Optional.empty();
        }

        List<LabPayload.Item> items = new ArrayList<>(rows.size());
        for (DecisionDatasetItemEntity row : rows) {
            items.add(payload.item(row));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", LabPayload.SCHEMA_VERSION);
        doc.put("kind", KIND_EVALUATION);
        doc.put("taskId", e.getId());
        doc.put("startedAt", e.getStartedAt());
        doc.put("dataset", datasetNode(e));
        doc.put("checkpoint", checkpointNode(e.getCheckpointId(), e.getCheckpointName(),
                e.getServeSlot(), e.getCheckpointPath()));
        doc.put("baseCheckpoint", e.getBaseCheckpointId() == null ? null
                : checkpointNode(e.getBaseCheckpointId(), e.getBaseCheckpointName(), null,
                        e.getBaseCheckpointPath()));
        doc.put("caseGroupCounts", caseGroupCounts(items));
        doc.put("items", items.stream().map(LabPayload.Item::node).toList());
        List<String> warnings = new ArrayList<>();
        String coverage = payload.coverageWarning(items);
        if (coverage != null) {
            warnings.add(coverage);
        }
        doc.put("warnings", warnings);

        Map<String, byte[]> files = scripts.readForEntry(LabScripts.EVAL_ENTRY);
        byte[] payloadBytes = payload.bytes(doc);
        byte[] zip = LabBundles.pack(
                new LabBundles.Manifest(LabScripts.EVAL_ENTRY, LabBundles.DEFAULT_PAYLOAD_NAME),
                files, payloadBytes);
        log.info("执行包构建: 评测 {} 脚本 {} 个 样本 {} 条 payload={} 字节 zip={} 字节", evalId,
                files.size(), items.size(), payloadBytes.length, zip.length);
        return Optional.of(new LabBundle("laya-eval-" + evalId + ".zip", zip));
    }

    /**
     * 微调包（FR-05）：训练脚本 + 「训练集（带切分标记）+ 超参 + 基座 + 产出位置」。
     *
     * <p><b>切分来自行里的 {@code val_item_ids_json}，不在这里现算</b>：切分是这次实验的事实
     * （见 {@code FinetuneSplit} 的注释），执行包照发即可；现算的话，包里的切分与入库的切分
     * 会成为两个可能不一致的东西，而报告是照着行写的。</p>
     */
    private Optional<LabBundle> finetune(String id) {
        Long ftId = parseId(id);
        if (ftId == null) {
            return Optional.empty();
        }
        DecisionFinetuneEntity f = finetuneRepo.findById(ftId).orElse(null);
        if (f == null) {
            return Optional.empty();
        }
        if (!DecisionFinetuneEntity.QUEUED.equals(f.getStatus())
                && !DecisionFinetuneEntity.RUNNING.equals(f.getStatus())) {
            log.info("微调 {} 状态 {} 已不需要执行，拒绝供包", ftId, f.getStatus());
            return Optional.empty();
        }
        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(f.getDatasetId());
        if (rows.isEmpty()) {
            log.warn("微调 {} 的训练集 {} 已无样本，无法构建执行包", ftId, f.getDatasetId());
            return Optional.empty();
        }
        Set<Long> valIds = parseIds(f.getValItemIdsJson());
        if (valIds.isEmpty()) {
            // 没有验证集 = 跑完只有训练损失，"学得怎么样"无从说起；宁可不跑
            log.warn("微调 {} 没有验证切分（val_item_ids_json 为空/读不出来），拒绝供包", ftId);
            return Optional.empty();
        }

        List<LabPayload.Item> items = new ArrayList<>(rows.size());
        int valSeen = 0;
        for (DecisionDatasetItemEntity row : rows) {
            boolean isVal = valIds.contains(row.getId());
            if (isVal) {
                valSeen++;
            }
            items.add(LabPayload.withSplit(payload.item(row),
                    isVal ? SPLIT_VAL : SPLIT_TRAIN));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", LabPayload.SCHEMA_VERSION);
        doc.put("kind", KIND_FINETUNE);
        doc.put("taskId", f.getId());
        doc.put("startedAt", f.getStartedAt());
        doc.put("dataset", datasetNode(f));
        doc.put("baseCheckpoint", checkpointNode(f.getBaseCheckpointId(), f.getBaseCheckpointName(),
                f.getBaseServeSlot(), f.getBaseCheckpointPath()));
        doc.put("serveSlot", f.getServeSlot());
        doc.put("outputPath", f.getOutputPath());
        doc.put("hyper", hyperNode(f));
        doc.put("split", splitNode(f, items.size(), valSeen));
        doc.put("caseGroupCounts", caseGroupCounts(items));
        doc.put("items", items.stream().map(LabPayload.Item::node).toList());
        List<String> warnings = new ArrayList<>();
        String coverage = payload.coverageWarning(items);
        if (coverage != null) {
            warnings.add(coverage);
        }
        if (valSeen != valIds.size()) {
            warnings.add("验证切分里有 " + (valIds.size() - valSeen)
                    + " 条样本在本集中已不存在（切分记录与样本对不上），本次实际考的样本比记录的少");
        }
        doc.put("warnings", warnings);

        Map<String, byte[]> files = scripts.readForEntry(LabScripts.FINETUNE_ENTRY);
        byte[] payloadBytes = payload.bytes(doc);
        byte[] zip = LabBundles.pack(
                new LabBundles.Manifest(LabScripts.FINETUNE_ENTRY, LabBundles.DEFAULT_PAYLOAD_NAME),
                files, payloadBytes);
        log.info("执行包构建: 微调 {} 脚本 {} 个 样本 {} 条（训练 {} / 验证 {}）payload={} 字节 zip={} 字节",
                ftId, files.size(), items.size(), items.size() - valSeen, valSeen,
                payloadBytes.length, zip.length);
        return Optional.of(new LabBundle("laya-train-" + ftId + ".zip", zip));
    }

    private static Map<String, Object> datasetNode(DecisionFinetuneEntity f) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", f.getDatasetId());
        node.put("name", f.getDatasetName());
        node.put("version", f.getDatasetVersion());
        node.put("questionSetVersion", f.getQuestionSetVersion());
        node.put("itemCount", f.getItemCount());
        return node;
    }

    /** 超参进包，脚本按它跑——命令行里也有一份（人要能在节点上直接看懂那条命令），两处同源同值 */
    private static Map<String, Object> hyperNode(DecisionFinetuneEntity f) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("epochs", f.getEpochs());
        node.put("learningRate", f.getLearningRate());
        node.put("batchSize", f.getBatchSize());
        node.put("trainSeed", f.getTrainSeed());
        node.put("launcher", f.getLauncher());
        return node;
    }

    /**
     * 切分段落。
     *
     * <p>只给数字与判据（seed/ratio），<b>不给 id 清单</b>：清单在库里（那是它的家），
     * 逐条样本上的 {@code split} 字段已经是"哪条属于哪边"的完整答案，
     * 再抄一份进包只会多一处可能对不上的地方。</p>
     */
    private static Map<String, Object> splitNode(DecisionFinetuneEntity f, int total, int valSeen) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("train", total - valSeen);
        node.put("val", valSeen);
        node.put("splitSeed", f.getSplitSeed());
        node.put("trainRatio", f.getTrainRatio());
        return node;
    }

    private Set<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<Number> parsed = mapper.readValue(json, new TypeReference<List<Number>>() {
            });
            Set<Long> ids = new LinkedHashSet<>();
            for (Number n : parsed) {
                if (n != null) {
                    ids.add(n.longValue());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("微调切分 id 清单解析失败（按无验证集处理，将拒绝供包）: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * 数据集段落取自评测行的快照列（名/版本/题面版本/条数），不是回查数据集行：
     * 报告里"这份数字是在哪份集上测的"必须与执行时一致，而快照列正是执行时抄下来的。
     */
    private static Map<String, Object> datasetNode(DecisionEvaluationEntity e) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", e.getDatasetId());
        node.put("name", e.getDatasetName());
        node.put("version", e.getDatasetVersion());
        node.put("questionSetVersion", e.getQuestionSetVersion());
        node.put("itemCount", e.getItemCount());
        return node;
    }

    private static Map<String, Object> checkpointNode(Long id, String name, String serveSlot, String path) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", name);
        node.put("serveSlot", serveSlot);
        node.put("path", path);
        return node;
    }

    private static Map<String, Object> caseGroupCounts(List<LabPayload.Item> items) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (LabPayload.Item item : items) {
            Object group = item.node().get("caseGroup");
            String key = group == null ? "(未分组)" : String.valueOf(group);
            counts.merge(key, 1, (a, b) -> ((Number) a).intValue() + 1);
        }
        return counts;
    }

    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
