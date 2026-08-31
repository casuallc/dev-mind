package com.devmind.docs.repo;

import com.devmind.docs.model.DocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();

    List<DocumentEntity> findByKindOrderByUpdatedAtDesc(String kind);

    List<DocumentEntity> findByStatusOrderByUpdatedAtDesc(String status);

    List<DocumentEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    /** P0-6：按任务聚合文档（任务主线视图） */
    List<DocumentEntity> findByRequirementIdOrderByUpdatedAtDesc(String requirementId);

    List<DocumentEntity> findByWorkItemIdOrderByUpdatedAtDesc(String workItemId);
}
