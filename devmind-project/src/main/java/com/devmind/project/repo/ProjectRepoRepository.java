package com.devmind.project.repo;

import com.devmind.project.model.ProjectRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepoRepository extends JpaRepository<ProjectRepoEntity, Long> {

    List<ProjectRepoEntity> findByProjectIdOrderBySortOrderAscIdAsc(String projectId);

    Optional<ProjectRepoEntity> findByProjectIdAndIsPrimaryTrue(String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndPath(String projectId, String path);

    void deleteByProjectId(String projectId);
}
