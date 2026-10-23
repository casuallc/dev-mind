package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeImportFileEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeImportFileRepository extends JpaRepository<KnowledgeImportFileEntity, Long> {

    List<KnowledgeImportFileEntity> findByBatchIdOrderByRelativePathAsc(String batchId);

    List<KnowledgeImportFileEntity> findByBatchIdAndStatus(String batchId, String status);

    long countByBatchIdAndStatus(String batchId, String status);

    List<KnowledgeImportFileEntity> findByEntryId(Long entryId);

    long countByStoredPath(String storedPath);
}
