package com.devmind.decisionlab.finetune.dto;

/**
 * CAP-56 FR-05 发起微调。
 *
 * <p>数值字段一律包装类型（可空 = 用平台默认 {@code devmind.decision-lab.*}）：超参是"想改才传"
 * 的东西，前端不传不代表要传 0。</p>
 *
 * @param datasetId       训练集（必填，须冻结；通常是从 {@code decision_records} 收编的回流集）
 * @param evalDatasetId   回评集（必填，须冻结）——<b>不能与训练集相同</b>：训练集的一部分被拿去训练了，
 *                        在那上面回评等于用练习题当考卷，指标只会好看。自动回评的意义就是换个集看泛化
 * @param baseCheckpointId 基座（必填；微调总得从某一份开始）
 * @param nodeId          指定 GPU 节点（可空 = 平台/标签路由）
 * @param requiredLabels  节点标签要求（CSV，如 {@code gpu,T4}；可空）
 * @param pythonPath      节点上的解释器（可空 = 平台配置 devmind.decision-lab.python-path）
 * @param outputPath      节点上的产出目录（<b>必填</b>：权重留节点，总得说清留在哪）
 * @param epochs          训练轮数（可空 = 默认）
 * @param learningRate    学习率（可空 = 默认）
 * @param batchSize       批大小（可空 = 默认）
 * @param trainSeed       训练随机种子（可空 = 默认；与 splitSeed 分开，见 {@code FinetuneSplit}）
 * @param splitSeed       训练/验证切分种子（可空 = 默认；切分结果会入库，不靠它复现）
 * @param trainRatio      训练集占比（可空 = 默认；0 与 1 都不允许）
 * @param launcher        多卡启动前缀（可空 = 单进程，如 {@code torchrun --nproc_per_node=2}）
 * @param timeoutSec      单次超时（秒，可空 = 平台默认；训练可能跑整夜，上限见 max-timeout-sec）
 */
public record FinetuneTriggerRequest(Long datasetId, Long evalDatasetId, Long baseCheckpointId,
                                     String nodeId, String requiredLabels, String pythonPath,
                                     String outputPath, Integer epochs, Double learningRate,
                                     Integer batchSize, Long trainSeed, Long splitSeed,
                                     Double trainRatio, String launcher, Long timeoutSec) {
}
