package com.devmind.serveradapter.repo;

import com.devmind.serveradapter.model.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByServerIdOrderByIdDesc(Long serverId, Pageable pageable);

    List<AuditLogEntity> findByProjectIdOrderByIdDesc(String projectId, Pageable pageable);

    List<AuditLogEntity> findByServerId(Long serverId);

    List<AuditLogEntity> findByProjectId(Long projectId);
}
