package com.devmind.agent.repo;

import com.devmind.agent.model.RunnerPackageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunnerPackageRepository extends JpaRepository<RunnerPackageEntity, Long> {
}
