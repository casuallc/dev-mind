package com.devmind.deploy.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建部署单（CAP-09 FR-01/04）：projectId 必填；目标二选一——serverId（直指服务器，兼容旧用法）
 * 或 environmentId（P1-1 环境：取其服务器组与变量注入，env 名以环境名为准）。
 */
public record CreateDeploymentRequest(
        @NotBlank String projectId,
        Long serverId,
        /** 目标环境 id（与 serverId 可同时传：校验服务器属于环境；缺省 serverId 取环境首台） */
        Long environmentId,
        Long buildId,
        String workItemId,
        String env,
        Boolean confirmRequired,
        Boolean force,
        /** 覆盖配置的临时计划（流程层用；缺省取项目部署配置渲染） */
        List<DeployStepRequest> plan) {
}
