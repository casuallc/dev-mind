package com.devmind.project.event;

import com.devmind.common.event.DomainEvent;

import java.time.Instant;

/**
 * 需求终态事件（CAP-51 FR-06 修订，P0-3 统一事件总线 DomainEvent）。人工验收 DONE / 取消
 * CANCELLED 时由 {@code RequirementService.updateStatus} 发布，devmind-session 监听后向执行
 * 节点下发 {@code workspace_release}（携带 {@code deleteRemoteBranch: true}）释放该需求的
 * 工作树并删除远端需求分支。清理失败<b>不阻断状态翻转</b>（需求已终态，不能让用户卡住），
 * 残留目录由 runner 侧 GC 兜底。
 *
 * <p>用强类型 record 而非 {@link com.devmind.common.event.SimpleDomainEvent}：一是要带
 * {@code workspaceOwner}（释放要定位 {@code <projectId>/<owner>/worktrees/<key>}，owner 冻结在
 * {@code requirements.workspace_owner} 列上），二是通知监听器只路由 SimpleDomainEvent，终态翻转
 * 是用户主动操作，不该再冒一条 P2 通知。</p>
 *
 * @param requirementId  进终态的需求 id
 * @param projectId      所属项目 id
 * @param workspaceOwner 需求工作区归属用户名（{@code requirements.workspace_owner}；空 = 无工作区）
 * @param status         终态值（DONE / CANCELLED）
 * @param actor          操作者（无登录态链路为 local）
 * @param occurredAt     事件时间
 */
public record RequirementTerminalEvent(String requirementId, String projectId, String workspaceOwner,
                                       String status, String actor, Instant occurredAt) implements DomainEvent {

    public RequirementTerminalEvent(String requirementId, String projectId, String workspaceOwner,
                                    String status, String actor) {
        this(requirementId, projectId, workspaceOwner, status, actor, Instant.now());
    }

    @Override
    public String type() {
        return "requirement.terminal";
    }
}
