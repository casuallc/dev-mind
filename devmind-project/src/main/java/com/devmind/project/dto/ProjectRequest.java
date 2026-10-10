package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 项目创建/更新请求。
 * CAP-23：sourceType=LOCAL（默认）时 path 必填且必须是本地 git 仓库；
 * sourceType=CLONE 时 path 由服务端计算（忽略请求值），remoteUrl 必填，integrationId 可选（null=匿名克隆公开仓库）。
 */
public record ProjectRequest(
        @NotBlank String name,
        String path,
        String sourceType,
        String remoteUrl,
        Long integrationId,
        String defaultBranch,
        List<String> tags,
        String description,
        String status,
        String apiDocSource,
        Boolean autoRegressionOnDeploy,
        /** CAP-72：WI 翻 DONE 后自动触发构建（不传=创建默认关/更新保持） */
        Boolean autoBuildOnWiDone,
        /** CAP-72：构建成功后自动部署 TEST 环境（不传=创建默认关/更新保持） */
        Boolean autoDeployTestOnBuild,
        /** CAP-21：默认执行节点 id（不传=保持不变，空串=清除回本机） */
        String agentNodeId,
        /** CAP-73：需求开发中验证总开关（不传=创建默认关/更新保持） */
        Boolean verifyInDevEnabled,
        /** CAP-73：验证日配额（不传=保持；0=不限；null 落库=默认 20） */
        Integer verifyDailyQuota) {
}
