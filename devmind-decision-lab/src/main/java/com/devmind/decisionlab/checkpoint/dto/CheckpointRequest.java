package com.devmind.decisionlab.checkpoint.dto;

/**
 * CAP-56 FR-06 登记一份决策模型产物。
 *
 * <p>{@code metricsJson} 是可选的原值（人工登记时可以把报告粘进来）；正常路径下它由
 * FR-03 评测或 FR-05 微调结束时自动写入，不靠人抄。</p>
 *
 * @param name        产物名（唯一；重训一份就换个名字，别覆盖——历史指标要指得回去）
 * @param serveSlot   服务槽位（边车加载它的位置，如 {@code multilingual}）
 * @param kind        {@code BASE}（HF 官方）/ {@code FINETUNED}（RLCD 产出）
 * @param sourcePath  HF repo id 或节点上的绝对路径
 * @param nodeId      权重所在的 runner 节点标识
 * @param fingerprintPath  权重目录（节点上的路径）
 * @param fingerprintBytes 权重字节数
 * @param fingerprintSha256 sha256（FINETUNED 必填：我们自己产出的东西一定有指纹）
 * @param metricsJson 指标报告（可空，通常由评测/微调任务写入）
 * @param note        备注
 */
public record CheckpointRequest(
        String name,
        String serveSlot,
        String kind,
        String sourcePath,
        String nodeId,
        String fingerprintPath,
        Long fingerprintBytes,
        String fingerprintSha256,
        String metricsJson,
        String note) {
}
