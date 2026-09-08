package com.devmind.common.agent;

import com.devmind.common.agent.exec.ContextManifest;

/**
 * CAP-33 FR-05 问答上下文装配 SPI（session 模块实现，chat 模块经 ObjectProvider 探测消费）：
 * chat 创建/恢复时据此获得 ContextPackage manifest 与快照，launch 帧挂 manifest 后
 * runner 拉包物化到 _chat/&lt;sid&gt; 沙箱（与 session 同一物化路径）。
 *
 * <p>不直接让 chat 实现 {@link ContextPackageProvider}——agent 模块拉包端点单注入该 SPI，
 * 多 bean 会炸；chat 的包仍由 session 侧统一装配与供给。</p>
 */
public interface ChatContextPreparer {

    /**
     * 场景预设解析：chat 据此把场景预设插进 model/permissionMode/节点优先级
     * （显式 &gt; 场景 &gt; 平台默认）。场景不存在抛 NOT_FOUND。
     */
    ScenarioPreset preset(String scenarioCode);

    /**
     * 装配并缓存上下文包，返回随 launch 帧下发的 manifest、FR-07 快照（供 chat 落库）与
     * 渲染后 prompt（场景骨架 {{task}}=首条消息；无骨架时原样返回 message，chat 据此作 launch prompt）。
     * scenarioCode 为 null 且无其它命中时返回 null（不带上下文启动，现状语义）。
     */
    PreparedContext prepare(String chatId, String scenarioCode, String message);

    /**
     * @param projectId 场景的上下文项目（PROJECT 场景「以某项目身份问答」；GLOBAL 场景为 null）
     */
    record ScenarioPreset(String model, String permissionMode, String agentNodeId, String projectId) {
    }

    record PreparedContext(ContextManifest manifest, String snapshotJson, String renderedPrompt) {
    }
}
