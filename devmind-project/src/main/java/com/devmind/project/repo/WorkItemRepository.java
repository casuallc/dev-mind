package com.devmind.project.repo;

import com.devmind.project.model.WorkItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkItemRepository extends JpaRepository<WorkItemEntity, String> {

    List<WorkItemEntity> findByRequirementIdOrderBySeqAsc(String requirementId);

    List<WorkItemEntity> findByProjectIdOrderBySeqDesc(String projectId);

    /** 项目内当前最大 seq（无工作项时 null） */
    @Query("select max(w.seq) from WorkItemEntity w where w.projectId = :projectId")
    Long findMaxSeqByProjectId(@Param("projectId") String projectId);

    void deleteByRequirementId(String requirementId);

    void deleteByProjectId(String projectId);
}
