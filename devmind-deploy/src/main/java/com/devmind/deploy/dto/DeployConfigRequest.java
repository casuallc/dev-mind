package com.devmind.deploy.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 部署计划配置写入（CAP-09 FR-01）：部署步骤 + 回滚步骤。 */
public record DeployConfigRequest(
        @NotNull List<DeployStepRequest> steps,
        @NotNull List<DeployStepRequest> rollbackSteps) {
}
