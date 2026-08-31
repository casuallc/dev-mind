package com.devmind.deploy.event;

/**
 * 部署终态事件（CAP-09 完成时发布）。CAP-10 监听后按项目 autoRegressionOnDeploy 自动触发测试回归
 * （FR-05）。用 Spring 事件解耦：deploy 模块不反向依赖 test 模块。
 *
 * @param deploymentId 部署记录 id
 * @param projectId    项目 id
 * @param serverId     目标服务器 id
 * @param success      部署成功（SUCCESS 终态，含回滚成功/失败均视为未成功）
 */
public record DeploymentCompletedEvent(Long deploymentId, String projectId, Long serverId, boolean success) {
}
