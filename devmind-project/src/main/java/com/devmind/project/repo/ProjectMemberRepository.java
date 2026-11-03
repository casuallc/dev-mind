package com.devmind.project.repo;

import com.devmind.project.model.ProjectMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, Long> {

    boolean existsByProjectIdAndUserId(String projectId, String userId);

    List<ProjectMemberEntity> findByProjectId(String projectId);

    void deleteByProjectId(String projectId);

    @Query("select m.projectId from ProjectMemberEntity m where m.userId = :userId")
    Set<String> findProjectIdsByUserId(@Param("userId") String userId);
}
