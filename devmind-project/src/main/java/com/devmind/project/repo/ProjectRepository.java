package com.devmind.project.repo;

import com.devmind.project.model.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ProjectEntity> findAllByOrderByCreatedAtDesc();

    long countByPath(String path);
}
