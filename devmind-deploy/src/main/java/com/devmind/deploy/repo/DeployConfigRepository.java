package com.devmind.deploy.repo;

import com.devmind.deploy.model.DeployConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeployConfigRepository extends JpaRepository<DeployConfigEntity, Long> {

    DeployConfigEntity findByProjectId(String projectId);
}
