package com.devmind.session.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<SessionEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** P0-6：按需求聚合会话（需求主线视图） */
    List<SessionEntity> findByTaskIdOrderByCreatedAtDesc(String taskId);
}
