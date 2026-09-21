package com.devmind.session.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<SessionEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** CAP-13：按工作单元聚合会话（需求主线视图） */
    List<SessionEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：按需求聚合会话（含分析型会话） */
    List<SessionEntity> findByRequirementIdOrderByCreatedAtDesc(String requirementId);

    /** CAP-27：批量按需求聚合（AI 实际耗时汇总用，避免列表 N+1） */
    List<SessionEntity> findByRequirementIdIn(java.util.Collection<String> requirementIds);

    /** CAP-34 FR-04：hello 对账 DB 兜底——按节点 + 活动状态查存量远程会话（服务端重启后内存 runtime 已丢失） */
    List<SessionEntity> findByAgentNodeIdAndStatusIn(String agentNodeId, java.util.Collection<String> statuses);

    /**
     * CAP-42：固定工作区占用预检——同 (项目, 归属用户) 未收口（OPEN）的会话。
     *
     * <p>CAP-51 起占用判定改为「同需求是否有进行中会话」（按状态，见
     * {@code SessionManagerService.precheckRequirementOccupancy}）——工作区归属粒度从用户改为需求，
     * 同需求多会话共用一棵工作树，按 OPEN 互斥会把「需求内串行复用」也挡掉（FR-03）。
     * 本方法保留为存量语义的查询入口，新逻辑不再调用。</p>
     */
    List<SessionEntity> findByProjectIdAndWorkspaceOwnerAndWorkspaceState(String projectId,
                                                                          String workspaceOwner,
                                                                          String workspaceState);
}
