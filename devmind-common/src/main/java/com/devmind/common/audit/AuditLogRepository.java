package com.devmind.common.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByServerIdOrderByIdDesc(Long serverId, Pageable pageable);

    List<AuditLogEntity> findByProjectIdOrderByIdDesc(String projectId, Pageable pageable);

    List<AuditLogEntity> findByServerId(Long serverId);

    List<AuditLogEntity> findByProjectId(Long projectId);

    List<AuditLogEntity> findByDomainOrderByIdDesc(String domain, Pageable pageable);
}
