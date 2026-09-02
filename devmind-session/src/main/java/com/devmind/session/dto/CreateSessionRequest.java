package com.devmind.session.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建会话请求。
 *
 * @param templateCode   会话模板 code（可选，命中后渲染 prompt 骨架）
 * @param projectId      项目 ID（MVP 只有一个预置项目；空=无项目裸跑，fake 模式）
 * @param workItemId     工作单元 ID（可选，CAP-13 关联约定；与 projectId 不一致时报错，projectId 空时反推）
 * @param requirementId  需求 ID（可选，分析型会话直挂需求；与 workItemId 同传时校验一致）
 * @param taskSpec       任务说明（富文本）
 * @param baseBranch     基准分支（可选，默认项目/配置）
 * @param model          模型（可选，覆盖全局）
 * @param permissionMode 权限模式（可选，覆盖全局）
 * @param agentNodeId    CAP-21 远程执行节点 ID（可选；空 = 本地子进程）
 */
public record CreateSessionRequest(
        String templateCode,
        String projectId,
        String workItemId,
        String requirementId,
        @NotBlank(message = "taskSpec 不能为空") String taskSpec,
        String baseBranch,
        String model,
        String permissionMode,
        String agentNodeId) {
}
