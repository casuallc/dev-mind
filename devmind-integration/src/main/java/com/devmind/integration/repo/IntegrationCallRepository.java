package com.devmind.integration.repo;

import com.devmind.integration.model.IntegrationCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationCallRepository extends JpaRepository<IntegrationCallEntity, Long> {

    List<IntegrationCallEntity> findByIntegrationIdOrderByIdDesc(Long integrationId);
}
