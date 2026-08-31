package com.devmind.deploy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 创建部署单（CAP-09 FR-01/04）：projectId+serverId 必填，buildId 可选（可手动回滚/无产物来源）。 */
public record CreateDeploymentRequest(
        @NotBlank String projectId,
        @NotNull Long serverId,
        Long buildId,
        String requirementId,
        String env,
        Boolean confirmRequired,
        Boolean force,
        /** 覆盖配置的临时计划（流程层用；缺省取项目部署配置渲染） */
        List<DeployStepRequest> plan) {
}
