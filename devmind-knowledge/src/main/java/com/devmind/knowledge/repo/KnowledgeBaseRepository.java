package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeBaseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from KnowledgeBaseEntity b where b.id = :id")
    java.util.Optional<KnowledgeBaseEntity> findForAccessUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    List<KnowledgeBaseEntity> findByStatusOrderByCreatedAtDesc(String status);

    /** CAP-48 删除端点前的引用检查（ModelEndpointUsageProvider 用） */
    List<KnowledgeBaseEntity> findByModelEndpointId(Long modelEndpointId);
}
