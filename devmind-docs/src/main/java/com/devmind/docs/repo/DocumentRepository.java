package com.devmind.docs.repo;

import com.devmind.docs.model.DocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();

    List<DocumentEntity> findByKindOrderByUpdatedAtDesc(String kind);

    List<DocumentEntity> findByStatusOrderByUpdatedAtDesc(String status);

    List<DocumentEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId);
}
