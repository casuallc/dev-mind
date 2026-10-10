package com.devmind.agent.repo;

import com.devmind.agent.model.AgentAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLogEntity, Long> {

    /**
     * 审计组合查询：category/event/nodeId/result 精确匹配（空=不限）；
     * keyword 对 detail/nodeName/remoteAddr/target 四列做大小写不敏感包含（均普通 varchar）。
     */
    @Query("""
            select a from AgentAuditLogEntity a
            where (:category is null or a.category = :category)
              and (:event is null or a.event = :event)
              and (:nodeId is null or a.nodeId = :nodeId)
              and (:result is null or a.result = :result)
              and (:keyword is null
                   or lower(a.detail) like lower(concat('%', :keyword, '%'))
                   or lower(a.nodeName) like lower(concat('%', :keyword, '%'))
                   or lower(a.remoteAddr) like lower(concat('%', :keyword, '%'))
                   or lower(a.target) like lower(concat('%', :keyword, '%')))
            order by a.createdAt desc, a.id desc
            """)
    Page<AgentAuditLogEntity> search(@Param("category") String category, @Param("event") String event,
                                     @Param("nodeId") Long nodeId, @Param("result") String result,
                                     @Param("keyword") String keyword, Pageable pageable);

    /** 保留期清理：删除早于 cutoff 的行，返回删除数。 */
    long deleteByCreatedAtBefore(Instant cutoff);
}
