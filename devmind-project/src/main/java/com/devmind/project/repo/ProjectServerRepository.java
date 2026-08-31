package com.devmind.project.repo;

import com.devmind.project.model.ProjectServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectServerRepository extends JpaRepository<ProjectServerEntity, Long> {

    List<ProjectServerEntity> findByProjectIdOrderByIdAsc(String projectId);

    void deleteByProjectId(String projectId);
}
