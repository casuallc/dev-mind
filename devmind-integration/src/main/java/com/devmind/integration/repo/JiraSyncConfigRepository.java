package com.devmind.integration.repo;

import com.devmind.integration.model.JiraSyncConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JiraSyncConfigRepository extends JpaRepository<JiraSyncConfigEntity, Long> {

    List<JiraSyncConfigEntity> findByProjectIdOrderByIdAsc(String projectId);

    Optional<JiraSyncConfigEntity> findByIntegrationIdAndProjectId(Long integrationId, String projectId);

    List<JiraSyncConfigEntity> findByEnabledTrue();
}
