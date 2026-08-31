package com.devmind.project.repo;

import com.devmind.project.model.ProjectLockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectLockRepository extends JpaRepository<ProjectLockEntity, String> {
}
