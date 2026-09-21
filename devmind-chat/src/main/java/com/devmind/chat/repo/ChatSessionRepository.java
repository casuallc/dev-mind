package com.devmind.chat.repo;

import com.devmind.chat.model.ChatSessionEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** 个人问答列表：按创建人 + 时间倒序 */
    List<ChatSessionEntity> findByCreatedByOrderByCreatedAtDesc(String createdBy);

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
}
