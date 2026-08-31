package com.devmind.project.repo;

import com.devmind.project.model.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByStatusOrderByUpdatedAtDesc(String status);

    List<ProjectEntity> findAllByOrderByUpdatedAtDesc();

    long countByPath(String path);
}
