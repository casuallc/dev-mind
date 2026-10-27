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
     * 可空筛选参数的 null 判断一律 cast：孤立 `? is null` 无列可推断参数类型，PG 报 42P18。
     */
    @Query("""
            select a from AgentAuditLogEntity a
            where (cast(:category as string) is null or a.category = :category)
              and (cast(:event as string) is null or a.event = :event)
              and (cast(:nodeId as long) is null or a.nodeId = :nodeId)
              and (cast(:result as string) is null or a.result = :result)
              and (cast(:keyword as string) is null
                   or lower(a.detail) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(a.nodeName) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(a.remoteAddr) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(a.target) like lower(concat('%', cast(:keyword as string), '%')))
            order by a.createdAt desc, a.id desc
            """)
    Page<AgentAuditLogEntity> search(@Param("category") String category, @Param("event") String event,
                                     @Param("nodeId") Long nodeId, @Param("result") String result,
                                     @Param("keyword") String keyword, Pageable pageable);

    /** 保留期清理：删除早于 cutoff 的行，返回删除数。 */
    long deleteByCreatedAtBefore(Instant cutoff);
}
