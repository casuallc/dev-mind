package com.devmind.deploy.event;

import com.devmind.common.event.DomainEvent;

import java.time.Instant;

/**
 * 部署终态事件（CAP-09 完成时发布，P0-3 起接入统一事件总线 DomainEvent）。
 * CAP-10 监听后按项目 autoRegressionOnDeploy 自动触发测试回归（FR-05）。
 * deploy 模块不反向依赖 test 模块。
 *
 * <p>注：部署的富通知（回滚成功/自动回滚等分级标题）由 DeploymentService 直发，
 * 通知监听器只路由 SimpleDomainEvent，本事件不重复通知。</p>
 *
 * @param deploymentId 部署记录 id
 * @param projectId    项目 id
 * @param serverId     目标服务器 id
 * @param success      部署成功（SUCCESS 终态，含回滚成功/失败均视为未成功）
 * @param occurredAt   事件时间
 */
public record DeploymentCompletedEvent(Long deploymentId, String projectId, Long serverId, boolean success,
                                       Instant occurredAt) implements DomainEvent {

    public DeploymentCompletedEvent(Long deploymentId, String projectId, Long serverId, boolean success) {
        this(deploymentId, projectId, serverId, success, Instant.now());
    }

    @Override
    public String type() {
        return "deploy.completed";
    }
}
