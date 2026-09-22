package com.devmind.common.decision;

import java.util.Optional;

/**
 * CAP-56 执行包查询 SPI（common 定义，devmind-decision-lab 实现，devmind-agent 的
 * {@code GET /api/agent/decision-lab/bundles/{kind}/{id}} 端点经 ObjectProvider 探测注入消费
 * ——agent 模块不反向依赖实验室）。
 *
 * <p>与 {@link LabBundle} 的分工：SPI 只说"给我某个任务的执行包"，至于包里装什么脚本、
 * 数据从哪几张表来（评测集条目 / 微调切分配置），是实验室自己的事。</p>
 *
 * <p><b>拉取时刻按需构建</b>：任务行在库里、数据集在库里，没人提前持有包的字节；HTTP 端点被调用时
 * 现构建一份（几十到几百 KB，节点上一次执行只拉一次）。未装配（模块没上线 / 滚动升级中）=
 * 端点 404 = 该节点的 lab 任务失败并说清原因，不静默降级。</p>
 */
public interface DecisionLabBundleProvider {

    /** 评测运行（CAP-56 FR-03）：数据集 + 评测脚本 */
    String KIND_EVALUATION = "evaluation";
    /** 微调任务（CAP-56 FR-05）：切分后的训练/验证集 + RLCD 训练脚本 */
    String KIND_FINETUNE = "finetune";

    /**
     * @param kind {@link #KIND_EVALUATION} / {@link #KIND_FINETUNE}
     * @param id   任务 id（评测运行 id / 微调任务 id）
     * @return 执行包；任务不存在/状态不该被执行（已结束）/数据不足 → empty（端点回 404）
     */
    Optional<LabBundle> labBundle(String kind, String id);
}
