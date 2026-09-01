package com.devmind.integration.repo;

import com.devmind.integration.model.IntegrationBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationBindingRepository extends JpaRepository<IntegrationBindingEntity, Long> {

    List<IntegrationBindingEntity> findByProjectIdOrderByIdAsc(String projectId);

    List<IntegrationBindingEntity> findByIntegrationId(Long integrationId);
}
