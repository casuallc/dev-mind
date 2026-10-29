package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeBaseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    List<KnowledgeBaseEntity> findByStatusOrderByCreatedAtDesc(String status);

    /** CAP-48 删除端点前的引用检查（ModelEndpointUsageProvider 用） */
    List<KnowledgeBaseEntity> findByModelEndpointId(Long modelEndpointId);
}
