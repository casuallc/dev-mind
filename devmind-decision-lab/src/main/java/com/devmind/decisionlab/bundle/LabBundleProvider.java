package com.devmind.decisionlab.bundle;

import com.devmind.common.decision.DecisionLabBundleProvider;
import com.devmind.common.decision.LabBundle;
import com.devmind.common.decision.LabBundles;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.lab.LabScripts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final DecisionEvaluationRepository evalRepo;
    private final DecisionDatasetItemRepository itemRepo;
    private final LabScripts scripts;
    private final LabPayload payload;

    public LabBundleProvider(DecisionEvaluationRepository evalRepo,
                             DecisionDatasetItemRepository itemRepo, LabScripts scripts,
                             LabPayload payload) {
        this.evalRepo = evalRepo;
        this.itemRepo = itemRepo;
        this.scripts = scripts;
        this.payload = payload;
    }

    @Override
    public Optional<LabBundle> labBundle(String kind, String id) {
        if (KIND_EVALUATION.equals(kind)) {
            return evaluation(id);
        }
        // 微调任务（FR-05）在 Phase 7 以同一形态接上：切分后的训练/验证集 + 训练脚本。
        // 现在返回 empty = 端点 404 = 该步骤失败并说清原因，不会静默跑一个空包
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
