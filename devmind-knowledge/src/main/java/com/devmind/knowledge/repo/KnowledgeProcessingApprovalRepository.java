package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeProcessingApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface KnowledgeProcessingApprovalRepository extends JpaRepository<KnowledgeProcessingApprovalEntity,Long> {
    List<KnowledgeProcessingApprovalEntity> findByResourceTypeAndResourceId(String resourceType,Long resourceId);
    void deleteByResourceTypeAndResourceId(String resourceType,Long resourceId);
}
