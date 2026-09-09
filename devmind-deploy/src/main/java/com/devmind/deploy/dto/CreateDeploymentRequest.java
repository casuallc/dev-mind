package com.devmind.deploy.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建部署单（CAP-09 FR-01/04）：projectId 必填；目标节点解析（CAP-36）——
 * agentNodeId（显式指定 runner 节点）> environmentId（P1-1 环境：取其节点组与变量注入，env 名以环境名为准）
 * > 皆空走节点路由链（项目默认 > 平台默认）。
 */
public record CreateDeploymentRequest(
        @NotBlank String projectId,
        /** 目标 runner 节点 id（与 environmentId 可同时传：校验节点属于环境节点组；缺省取环境首个节点） */
        String agentNodeId,
        Long environmentId,
        Long buildId,
        String workItemId,
        String env,
        Boolean confirmRequired,
        Boolean force,
        /** 覆盖配置的临时计划（流程层用；缺省取项目部署配置渲染） */
        List<DeployStepRequest> plan) {
}
