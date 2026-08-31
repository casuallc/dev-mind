package com.devmind.project.repo;

import com.devmind.project.model.RequirementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RequirementRepository extends JpaRepository<RequirementEntity, String> {

    List<RequirementEntity> findByProjectIdOrderBySeqDesc(String projectId);

    List<RequirementEntity> findByProjectIdAndStatusOrderBySeqDesc(String projectId, String status);

    /** 项目内当前最大 seq（无需求时 null） */
    @Query("select max(r.seq) from RequirementEntity r where r.projectId = :projectId")
    Long findMaxSeqByProjectId(@Param("projectId") String projectId);

    void deleteByProjectId(String projectId);
}
