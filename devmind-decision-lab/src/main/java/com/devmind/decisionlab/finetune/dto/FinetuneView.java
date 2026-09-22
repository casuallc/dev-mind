package com.devmind.decisionlab.finetune.dto;

import com.devmind.decisionlab.eval.dto.EvalView;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import java.time.Instant;
import java.util.Map;

/**
 * CAP-56 微调任务视图（列表行 / 详情头部）。
 *
 * <p><b>切分数字（train/val）必须显示</b>：一次微调的报告里那个 val 指标，只有在"验证是哪几条"
 * 说得清的时候才是一份证据。列表上直接给出 {@code 12/3} 这样的切分，比藏在详情里更能让人
 * 意识到"这个指标是在 3 条上算的"。</p>
 *
 * <p><b>{@code postLabel} 是跑完之后那两件事的结论</b>：训练成功却没有产物（脚本没打指纹）、
 * 产物登记了却没能触发回评——这些不并进失败，但必须一眼可见，否则人对着一条 SUCCESS
 * 会去找一个不存在的产物。</p>
 *
 * @param postLabel 收尾结论的人读文案（训练失败 = 空；见 {@link #postLabel}）
 */
public record FinetuneView(Long id, Long datasetId, String datasetLabel, String questionSetVersion,
                           Integer itemCount, Integer trainCount, Integer valCount,
                           Long splitSeed, Double trainRatio,
                           Long evalDatasetId, String evalDatasetLabel,
                           Long baseCheckpointId, String baseCheckpointLabel,
                           String serveSlot, String outputPath,
                           Integer epochs, Double learningRate, Integer batchSize,
                           Long trainSeed, String launcher,
                           String nodeId, String status, String reportStatus, String reportLabel,
                           Map<String, Object> headline,
                           Long checkpointId, String checkpointName, Long evalId,
                           String postLabel, String postError,
                           Integer exitCode, String errorSummary, Long timeoutSeconds,
                           String createdBy, Instant createdAt, Instant startedAt, Instant finishedAt) {

    public static FinetuneView of(DecisionFinetuneEntity e, Map<String, Object> headline) {
        return new FinetuneView(
                e.getId(),
                e.getDatasetId(), label(e.getDatasetName(), e.getDatasetVersion()),
                e.getQuestionSetVersion(),
                e.getItemCount(), e.getTrainCount(), e.getValCount(),
                e.getSplitSeed(), e.getTrainRatio(),
                e.getEvalDatasetId(), label(e.getEvalDatasetName(), e.getEvalDatasetVersion()),
                e.getBaseCheckpointId(), e.getBaseCheckpointName(),
                e.getServeSlot(), e.getOutputPath(),
                e.getEpochs(), e.getLearningRate(), e.getBatchSize(), e.getTrainSeed(), e.getLauncher(),
                e.getNodeId(), e.getStatus(), e.getReportStatus(),
                EvalView.reportLabel(e.getReportStatus(), e.getStatus(), e.getExitCode()),
                headline,
                e.getCheckpointId(), e.getCheckpointName(), e.getEvalId(),
                postLabel(e), e.getPostError(),
                e.getExitCode(), e.getErrorSummary(), e.getTimeoutSeconds(),
                e.getCreatedBy(), e.getCreatedAt(), e.getStartedAt(), e.getFinishedAt());
    }

    /**
     * 收尾结论：跑完之后该登记产物、该触发回评，做了几件。
     *
     * <p>顺序上先报错、再说缺哪一件、最后才说做成了什么——一条 SUCCESS 上"没登记产物"
     * 比"已触发回评"重要得多，不能因为后者看着圆满就排在前面。</p>
     */
    static String postLabel(DecisionFinetuneEntity e) {
        if (!DecisionFinetuneEntity.SUCCESS.equals(e.getStatus())) {
            return null;
        }
        if (e.getCheckpointId() == null) {
            return "训练跑完但未登记产物（见下方原因）";
        }
        if (e.getEvalId() == null) {
            return "产物 " + e.getCheckpointName() + " 已登记，但自动回评未触发（见下方原因）";
        }
        return "产物 " + e.getCheckpointName() + " 已登记，自动回评 #" + e.getEvalId();
    }

    private static String label(String name, Integer version) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return version == null ? name : name + " v" + version;
    }
}
