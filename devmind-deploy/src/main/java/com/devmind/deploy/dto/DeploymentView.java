package com.devmind.deploy.dto;

import com.devmind.project.dto.WorkItemBrief;

import java.time.Instant;
import java.util.List;

/** 部署单详情视图：计划（可见）+ 逐步骤实时状态。 */
public record DeploymentView(
        Long id,
        String projectId,
        String workItemId,
        String agentNodeId,
        Long environmentId,
        Long buildId,
        String env,
        String status,
        Integer currentStep,
        String backupRef,
        Long rollbackOf,
        boolean confirmRequired,
        boolean confirmed,
        String errorSummary,
        String createdBy,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        List<DeployStepRequest> plan,
        List<StepView> steps,
        /** 关联工作单元摘要（列表回链需求展示；未关联或已删除时为 null） */
        WorkItemBrief workItem) {
}
