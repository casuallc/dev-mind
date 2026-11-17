package com.devmind.chat.repo;

import com.devmind.chat.model.ChatSessionEntity;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** 个人问答列表：按创建人 + 时间倒序 */
    List<ChatSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    /** REQ-23：分页重载（列表页只取最近一页） */
    List<ChatSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy, Pageable pageable);

    /** REQ-23：分页重载（状态过滤服务端化——窗口化后客户端再滤会漏数据） */
    List<ChatSessionEntity> findByCreatedByAndStatusOrderByCreatedAtDesc(String createdBy, String status,
                                                                         Pageable pageable);

    /** REQ-23：活跃合并——当前用户的 RUNNING/WAITING_INPUT/WAITING_AUTH 恒在列表结果内 */
    List<ChatSessionEntity> findByCreatedByAndStatusInOrderByCreatedAtDesc(String createdBy,
                                                                           java.util.Collection<String> statuses);

    /**
     * REQ-23：关键词搜索服务端化（字段 id/title/summary；窗口化后前端本地搜会漏）。
     * summary 是 @Lob，lower 前必须 cast 成 string（红线，同 SessionRepository.search 的既有写法）；
     * title 是普通 VARCHAR，直接 lower 即可。
     */
    @Query("select s from ChatSessionEntity s where s.createdBy = :createdBy "
            + "and (s.id like concat('%', cast(:kw as string), '%') "
            + "or lower(s.title) like lower(concat('%', cast(:kw as string), '%')) "
            + "or lower(cast(s.summary as string)) like lower(concat('%', cast(:kw as string), '%'))) "
            + "order by s.createdAt desc")
    List<ChatSessionEntity> search(@Param("createdBy") String createdBy, @Param("kw") String kw, Pageable pageable);

    /** CAP-34 FR-04：hello 对账 DB 兜底——按节点 + 活动状态查存量远程问答（服务端重启后内存 runtime 已丢失） */
    List<ChatSessionEntity> findByAgentNodeIdAndStatusIn(String agentNodeId, java.util.Collection<String> statuses);

    /** CAP-48 FR-01：端点引用方查询（模型执行体的问答按 status 过滤，只要"进行中"的） */
    List<ChatSessionEntity> findByModelEndpointIdAndStatusIn(Long modelEndpointId,
                                                             java.util.Collection<String> statuses);

    /**
     * CAP-49：只写实时状态（status/updated_at，并清 finished_at —— 能写下活动状态就说明"还没结束"）。
     * 不读实体、不碰 summary。
     *
     * <p>用批量 UPDATE 而不是 {@code save(entity)} 有两个原因：一是模型执行体的状态变更发生在
     * <b>生成线程</b>上（每轮 2 次），读-改-写会与其它写方互踩；二是删除问答的事务里若并发完成
     * 一轮生成，{@code save} 的 merge 会把已删除的行重新插回来，而 UPDATE 命中 0 行 —— 什么都不做。</p>
     *
     * @return 受影响行数（0 = 该问答已不存在）
     */
    @Transactional
    @Modifying
    @Query("update ChatSessionEntity e set e.status = :status, e.updatedAt = :now, e.finishedAt = null "
            + "where e.id = :id")
    int updateLiveStatus(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

    /**
     * 用量账本入账：回合 result 的用量累加进问答累计列。批量 UPDATE 的理由同 {@link #updateLiveStatus}
     * （生成线程并发 + 删除事务防 merge 回插）；AGENT 执行体报 cost/tokens，MODEL 执行体只计回合。
     *
     * @return 受影响行数（0 = 问答已不存在）
     */
    @Transactional
    @Modifying
    @Query("update ChatSessionEntity e set "
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

    /**
     * CAP-84 FR-20：只写最近一轮知识检索的降级原因（会话顶部提示用）。
     * 批量 UPDATE 的理由同 {@link #updateLiveStatus}——检索发生在提问线程，不能整实体 save 互踩。
     */
    @Transactional
    @Modifying
    @Query("update ChatSessionEntity e set e.knowledgeDegradedReason = :reason, e.updatedAt = :now "
            + "where e.id = :id")
    int updateKnowledgeDegraded(@Param("id") String id, @Param("reason") String reason,
                                @Param("now") Instant now);
}
