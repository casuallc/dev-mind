package com.devmind.deploy.dto;

import java.time.Instant;
import java.util.List;

/** 部署计划配置视图。 */
public record DeployConfigView(
        String projectId,
        List<DeployStepRequest> steps,
        List<DeployStepRequest> rollbackSteps,
        Instant updatedAt) {
}
