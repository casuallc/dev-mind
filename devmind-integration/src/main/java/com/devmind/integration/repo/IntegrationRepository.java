package com.devmind.integration.repo;

import com.devmind.integration.model.IntegrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationRepository extends JpaRepository<IntegrationEntity, Long> {

    List<IntegrationEntity> findAllByOrderByIdAsc();
}
