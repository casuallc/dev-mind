package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface KnowledgeAuditRepository extends JpaRepository<KnowledgeAuditEntity,Long> {
    List<KnowledgeAuditEntity> findTop100ByActorOrderByCreatedAtDesc(String actor);
    List<KnowledgeAuditEntity> findTop100ByOrderByCreatedAtDesc();
}
