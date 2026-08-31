package com.devmind.project.repo;

import com.devmind.project.model.ReleaseConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReleaseConfigRepository extends JpaRepository<ReleaseConfigEntity, Long> {

    Optional<ReleaseConfigEntity> findByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
