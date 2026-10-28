package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeProductVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface KnowledgeProductVersionRepository extends JpaRepository<KnowledgeProductVersionEntity,Long> {
    List<KnowledgeProductVersionEntity> findByProductIdOrderBySortOrderAsc(String productId);
}
