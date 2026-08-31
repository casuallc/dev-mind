package com.devmind.project.repo;

import com.devmind.project.model.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByProjectIdOrderBySeqDesc(String projectId);

    List<TaskEntity> findByProjectIdAndStatusOrderBySeqDesc(String projectId, String status);

    /** 项目内当前最大 seq（无任务时 null） */
    @Query("select max(t.seq) from TaskEntity t where t.projectId = :projectId")
    Long findMaxSeqByProjectId(@Param("projectId") String projectId);

    void deleteByProjectId(String projectId);
}
