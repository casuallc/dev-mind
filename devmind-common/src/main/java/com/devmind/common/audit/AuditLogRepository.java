package com.devmind.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 审计组合查询：三条件均可空（空=不限），按 id 倒序。 */
    @Query("""
            select a from AuditLogEntity a
            where (:serverId is null or a.serverId = :serverId)
              and (:projectId is null or a.projectId = :projectId)
              and (:action is null or a.action = :action)
            order by a.id desc
            """)
    Page<AuditLogEntity> search(@Param("serverId") Long serverId, @Param("projectId") String projectId,
                                @Param("action") String action, Pageable pageable);

    List<AuditLogEntity> findByServerId(Long serverId);

    List<AuditLogEntity> findByProjectId(Long projectId);

    List<AuditLogEntity> findByDomainOrderByIdDesc(String domain, Pageable pageable);
}
