package com.devmind.deploy.dto;

import java.time.Instant;
import java.util.List;

/** 部署单详情视图：计划（可见）+ 逐步骤实时状态。 */
public record DeploymentView(
        Long id,
        String projectId,
        String requirementId,
        Long serverId,
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
        List<StepView> steps) {
}
