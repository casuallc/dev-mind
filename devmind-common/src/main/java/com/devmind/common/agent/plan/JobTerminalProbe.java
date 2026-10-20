package com.devmind.common.agent.plan;

/**
 * 异步作业终态轮询探针（CAP-76 FR-35）：实现随能力模块交付（BUILD→devmind-build、
 * SESSION→devmind-session），copilot 以 {@code ObjectProvider<JobTerminalProbe>} 收集，
 * 计划步骤的 {@code await} 按 {@link #kind()} 匹配探针轮询终态。
 *
 * <p>实现必须是<b>只读查询</b>，内部用入参 actor 合成 SecurityContext（姿势同
 * CopilotToolRunner）；幂等——同一 id 任意频次调用无副作用。kind 重复注册由收集方
 * 启动期 fail-fast。</p>
 */
public interface JobTerminalProbe {

    /** 作业类型：BUILD / SESSION / DEPLOYMENT / TEST_RUN。 */
    String kind();

    /**
     * 判定作业当前状态。
     *
     * @param entityId 作业 id（构建号/会话 id 等）
     * @param actor    计划发起人（实现方据此合成 SecurityContext）
     * @return UNKNOWN = 记录不存在/无法判定（调用方按 RUNNING 继续等）
     */
    JobState probe(String entityId, String actor);

    /** FAILED 时的错误摘要（推送与中断回执用；可空）。 */
    default String failureSummary(String entityId) {
        return null;
    }
}
