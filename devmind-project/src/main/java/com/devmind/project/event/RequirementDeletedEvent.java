package com.devmind.project.event;

import com.devmind.common.event.DomainEvent;

import java.time.Instant;

/**
 * 需求删除事件（CAP-51 FR-06，P0-3 统一事件总线 DomainEvent）。需求行已删、事务尚未提交时发布，
 * devmind-session 监听后向执行节点下发 {@code workspace_release} 释放该需求的工作树（丢弃语义：
 * 不合并不 push）。释放失败<b>不阻断删除</b>（需求已删，不能让用户卡住），残留目录由 runner 侧 GC 兜底。
 *
 * <p>用强类型 record 而非 {@link com.devmind.common.event.SimpleDomainEvent}：一是要带
 * {@code workspaceOwner}（释放要定位 {@code <projectId>/<owner>/worktrees/<key>}，需求行已删查不回来），
 * 二是通知监听器只路由 SimpleDomainEvent，删除是用户主动操作，不该再冒一条 P2 通知。</p>
 *
 * @param requirementId  被删除的需求 id
 * @param projectId      所属项目 id
 * @param workspaceOwner 需求工作区归属用户名（{@code requirements.workspace_owner}；空 = 无工作区）
 * @param actor          删除操作者（无登录态链路为 local）
 * @param occurredAt     事件时间
 */
public record RequirementDeletedEvent(String requirementId, String projectId, String workspaceOwner,
                                      String actor, Instant occurredAt) implements DomainEvent {

    public RequirementDeletedEvent(String requirementId, String projectId, String workspaceOwner,
                                   String actor) {
        this(requirementId, projectId, workspaceOwner, actor, Instant.now());
    }

    @Override
    public String type() {
        return "requirement.deleted";
    }
}
