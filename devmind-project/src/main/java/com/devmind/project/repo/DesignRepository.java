package com.devmind.project.repo;

import com.devmind.project.model.DesignEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DesignRepository extends JpaRepository<DesignEntity, String> {

    List<DesignEntity> findByRequirementIdOrderByVersionDesc(String requirementId);

    /** requirement 内当前最大版本号（无方案时 null） */
    @Query("select max(d.version) from DesignEntity d where d.requirementId = :requirementId")
    Integer findMaxVersionByRequirementId(@Param("requirementId") String requirementId);

    void deleteByRequirementId(String requirementId);

    void deleteByProjectId(String projectId);
}
