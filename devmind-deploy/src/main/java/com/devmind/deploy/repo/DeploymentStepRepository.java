package com.devmind.deploy.repo;

import com.devmind.deploy.model.DeploymentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentStepRepository extends JpaRepository<DeploymentStepEntity, Long> {

    List<DeploymentStepEntity> findByDeploymentIdOrderBySeqAsc(Long deploymentId);
}
