package com.devmind.decisionlab.eval.dto;

/**
 * CAP-56 发起评测。
 *
 * <p>布尔字段一律 {@code Boolean} 包装（Jackson 3 红线：请求体里显式 null → primitive 直接抛错，
 * 而"没传这个字段"是合法输入，含义是"按默认"）。</p>
 *
 * @param checkpointId    被测 checkpoint（必填，须已登记；是否通过验证与本次评测无关——
 *                        评测恰恰是拿到验证依据的手段）
 * @param datasetId       评测集（必填，且必须是<b>冻结</b>态：指标只有在输入固定时才可比）
 * @param baseCheckpointId 对照基线 checkpoint（可空 = 不做逐题胜负比对）
 * @param nodeId          指定节点（可空 = 平台/标签路由；评测总在 GPU 节点上跑，一般显式指定）
 * @param requiredLabels  节点标签要求（CSV，如 {@code gpu,T4}；可空）
 * @param pythonPath      节点上的解释器（可空 = 用平台配置 devmind.decision-lab.python-path）
 * @param outputPath      节点上的产出目录（可空 = 不在节点留原始报告文件，只回传指标）
 * @param timeoutSec      单次超时（秒，可空 = 平台默认；上限见 devmind.decision-lab.max-timeout-sec）
 * @param fitTemperature  是否顺带做温度校准（真机拟合用 held-out 切分，结果写回报告与 checkpoint 目录）
 */
public record EvalTriggerRequest(Long checkpointId, Long datasetId, Long baseCheckpointId, String nodeId,
                                 String requiredLabels, String pythonPath, String outputPath,
                                 Long timeoutSec, Boolean fitTemperature) {
}
