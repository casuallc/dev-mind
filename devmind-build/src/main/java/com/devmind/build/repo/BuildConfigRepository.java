package com.devmind.build.repo;

import com.devmind.build.model.BuildConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BuildConfigRepository extends JpaRepository<BuildConfigEntity, Long> {

    Optional<BuildConfigEntity> findByProjectId(String projectId);
}
