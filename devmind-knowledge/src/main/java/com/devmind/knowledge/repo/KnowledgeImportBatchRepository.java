package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeImportBatchEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeImportBatchRepository extends JpaRepository<KnowledgeImportBatchEntity, String> {

    List<KnowledgeImportBatchEntity> findAllByOrderByCreatedAtDesc();

    List<KnowledgeImportBatchEntity> findByKbIdOrderByCreatedAtDesc(Long kbId);
}
