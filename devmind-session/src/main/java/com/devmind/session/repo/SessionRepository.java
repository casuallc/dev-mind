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

    /** CAP-27：批量按需求聚合（AI 实际耗时汇总用，避免列表 N+1） */
    List<SessionEntity> findByRequirementIdIn(java.util.Collection<String> requirementIds);
}
