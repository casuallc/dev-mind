package com.devmind.docs.repo;

import com.devmind.docs.model.DocumentVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, Long> {

    List<DocumentVersionEntity> findByDocumentIdOrderByVersionNoDesc(Long documentId);

    Optional<DocumentVersionEntity> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(Long documentId, int versionNo);

    void deleteByDocumentId(Long documentId);
}
