package com.devmind.project.repo;

import com.devmind.project.model.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ProjectEntity> findAllByOrderByCreatedAtDesc();

    long countByPath(String path);

    /** CAP-41：按种类 + 归属用户查（WORKLOG 项目每用户至多一个） */
    java.util.Optional<ProjectEntity> findByKindAndOwnerId(String kind, String ownerId);
}
