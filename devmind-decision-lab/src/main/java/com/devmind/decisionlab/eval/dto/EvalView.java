package com.devmind.decisionlab.eval.dto;

import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import java.time.Instant;
import java.util.Map;

/**
 * CAP-56 评测运行视图（列表行）。
 *
 * <p>{@code headline} 是列表页要显示的那几个数字（准确率 / 两条基线 / 校准后 ECE / 逐题胜负），
 * 来自落库时摘出来的 {@code headline_json}——列表不该为了渲染一行去解析一份完整报告。</p>
 *
 * @param datasetLabel  评测集的"名字 v版本"（报告标题必须自带版本：集可以修订，数字不会跟着变）
 * @param checkpointLabel 被测 checkpoint 的名字（含槽位，槽位是它实际服务的位置）
 * @param baseCheckpointLabel 对照基线名字（空 = 没做对照）
 * @param reportLabel   报告状态的人读文案（OK/缺失/残缺 都要能一眼看见）
 */
public record EvalView(Long id, Long datasetId, String datasetLabel, String questionSetVersion,
                       Long checkpointId, String checkpointLabel, String serveSlot,
                       Long baseCheckpointId, String baseCheckpointLabel,
                       String nodeId, String status, Integer itemCount, String reportStatus,
                       String reportLabel, Map<String, Object> headline,
                       Integer exitCode, String errorSummary, Long timeoutSeconds,
                       String createdBy, Instant createdAt, Instant startedAt, Instant finishedAt) {

    public static EvalView of(DecisionEvaluationEntity e, Map<String, Object> headline) {
        return new EvalView(e.getId(), e.getDatasetId(),
                label(e.getDatasetName(), e.getDatasetVersion()), e.getQuestionSetVersion(),
                e.getCheckpointId(), label(e.getCheckpointName(), null), e.getServeSlot(),
                e.getBaseCheckpointId(), e.getBaseCheckpointName(),
                e.getNodeId(), e.getStatus(), e.getItemCount(), e.getReportStatus(),
                reportLabel(e.getReportStatus(), e.getStatus(), e.getExitCode()),
                headline, e.getExitCode(), e.getErrorSummary(), e.getTimeoutSeconds(),
                e.getCreatedBy(), e.getCreatedAt(), e.getStartedAt(), e.getFinishedAt());
    }

    private static String label(String name, Integer version) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return version == null ? name : name + " v" + version;
    }

    /**
     * 报告状态文案。
     *
     * <p>FAILED 时不重复说报告的事（"运行失败"已经说清了，再说"报告缺失"像是在报两件事）；
     * SUCCESS 却没有报告才是必须显眼的那一种：跑完了、退出码 0、页面上却什么都没有——
     * 不写明白就会被当成"模型全错"。</p>
     */
    public static String reportLabel(String reportStatus, String status, Integer exitCode) {
        if (DecisionEvaluationEntity.FAILED.equals(status)) {
            return "运行失败";
        }
        String s = reportStatus == null ? "" : reportStatus;
        return switch (s) {
            case DecisionEvaluationEntity.REPORT_OK -> "报告齐备";
            case DecisionEvaluationEntity.REPORT_MISSING ->
                    "跑完但无报告（脚本未打印 DEVMIND_REPORT 行）";
            case DecisionEvaluationEntity.REPORT_MALFORMED ->
                    "报告无法解析（载荷损坏或脚本版本不一致）";
            case DecisionEvaluationEntity.REPORT_INCOMPLETE ->
                    "报告缺必报项（指标或基线缺失）";
            default -> DecisionEvaluationEntity.RUNNING.equals(status) ? "运行中"
                    : DecisionEvaluationEntity.QUEUED.equals(status) ? "排队中"
                    : "报告状态未知（exit=" + exitCode + "）";
        };
    }
}
