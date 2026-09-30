package com.devmind.session.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * 用量账本入账：回合 result 的用量累加进会话累计列。
     *
     * <p>用批量 UPDATE 而不是 {@code save(entity)}：入账发生在事件读取线程上，与 onExit/updateStatus
     * 的读-改-写并发时整实体 save 会互踩（丢状态或丢用量）；纯 SQL 累加在 DB 层原子，且会话被并发
     * 删除时命中 0 行自然落空，不会把行 merge 回来（与 chat 侧 updateLiveStatus 同款考量）。</p>
     *
     * @return 受影响行数（0 = 会话已不存在）
     */
    @Transactional
    @Modifying
    @Query("update SessionEntity e set "
            + "e.costUsd = coalesce(e.costUsd, 0) + :cost, "
            + "e.inputTokens = coalesce(e.inputTokens, 0) + :inputTokens, "
            + "e.outputTokens = coalesce(e.outputTokens, 0) + :outputTokens, "
            + "e.cacheReadTokens = coalesce(e.cacheReadTokens, 0) + :cacheReadTokens, "
            + "e.cacheCreationTokens = coalesce(e.cacheCreationTokens, 0) + :cacheCreationTokens, "
            + "e.turnCount = coalesce(e.turnCount, 0) + 1, "
            + "e.updatedAt = :now "
            + "where e.id = :id")
    int addUsage(@Param("id") String id,
                 @Param("cost") double cost,
                 @Param("inputTokens") long inputTokens,
                 @Param("outputTokens") long outputTokens,
                 @Param("cacheReadTokens") long cacheReadTokens,
                 @Param("cacheCreationTokens") long cacheCreationTokens,
                 @Param("now") Instant now);
}
