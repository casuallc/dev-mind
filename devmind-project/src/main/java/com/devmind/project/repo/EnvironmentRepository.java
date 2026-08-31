package com.devmind.project.repo;

import com.devmind.project.model.EnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, Long> {

    List<EnvironmentEntity> findByProjectIdOrderByIdAsc(String projectId);

    Optional<EnvironmentEntity> findByProjectIdAndName(String projectId, String name);

    void deleteByProjectId(String projectId);
}
