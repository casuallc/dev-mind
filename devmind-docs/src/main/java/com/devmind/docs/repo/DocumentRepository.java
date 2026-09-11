package com.devmind.docs.repo;

import com.devmind.docs.model.DocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();

    List<DocumentEntity> findByKindOrderByCreatedAtDesc(String kind);

    List<DocumentEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<DocumentEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** P0-6：按任务聚合文档（任务主线视图） */
    List<DocumentEntity> findByRequirementIdOrderByCreatedAtDesc(String requirementId);

    /** CAP-37：需求下某 kind 最新一份（流程引擎取分析产出注入下游会话） */
    java.util.Optional<DocumentEntity> findFirstByRequirementIdAndKindOrderByCreatedAtDesc(String requirementId, String kind);

    List<DocumentEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);
}
