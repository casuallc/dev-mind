package com.devmind.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 审计组合查询：三条件均可空（空=不限），按 id 倒序。
     *  可空筛选参数的 null 判断一律 cast：孤立 `? is null` 无列可推断参数类型，PG 报 42P18。 */
    @Query("""
            select a from AuditLogEntity a
            where (cast(:serverId as long) is null or a.serverId = :serverId)
              and (cast(:projectId as string) is null or a.projectId = :projectId)
              and (cast(:action as string) is null or a.action = :action)
            order by a.id desc
            """)
    Page<AuditLogEntity> search(@Param("serverId") Long serverId, @Param("projectId") String projectId,
                                @Param("action") String action, Pageable pageable);

    List<AuditLogEntity> findByServerId(Long serverId);

    List<AuditLogEntity> findByProjectId(Long projectId);

    List<AuditLogEntity> findByDomainOrderByIdDesc(String domain, Pageable pageable);
}
