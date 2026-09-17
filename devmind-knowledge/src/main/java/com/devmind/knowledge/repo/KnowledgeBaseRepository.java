package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeBaseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    List<KnowledgeBaseEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<KnowledgeBaseEntity> findByScopeAndInjectModeAndStatus(String scope, String injectMode, String status);

    List<KnowledgeBaseEntity> findByScopeAndProjectIdAndInjectModeAndStatus(
            String scope, String projectId, String injectMode, String status);

    List<KnowledgeBaseEntity> findByScopeAndProjectId(String scope, String projectId);

    List<KnowledgeBaseEntity> findByScopeAndName(String scope, String name);
}
