package com.devmind.session.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<SessionEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** CAP-13：按工作单元聚合会话（需求主线视图） */
    List<SessionEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：按需求聚合会话（含分析型会话） */
    List<SessionEntity> findByRequirementIdOrderByCreatedAtDesc(String requirementId);
}
