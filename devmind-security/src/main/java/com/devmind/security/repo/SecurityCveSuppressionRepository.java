package com.devmind.security.repo;

import com.devmind.security.model.SecurityCveSuppressionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SecurityCveSuppressionRepository extends JpaRepository<SecurityCveSuppressionEntity, Long> {

    /** 项目生效集合：项目级 + 平台级（project_id IS NULL） */
    @Query("select e from SecurityCveSuppressionEntity e where e.projectId = :projectId or e.projectId is null")
    List<SecurityCveSuppressionEntity> findEffectiveForProject(@Param("projectId") String projectId);

    List<SecurityCveSuppressionEntity> findByProjectId(String projectId);

    List<SecurityCveSuppressionEntity> findByProjectIdIsNull();

    List<SecurityCveSuppressionEntity> findByGroupIdAndArtifactIdAndCve(String groupId, String artifactId, String cve);
}
