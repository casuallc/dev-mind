package com.devmind.project.repo;

import com.devmind.project.model.BuildStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildStepRepository extends JpaRepository<BuildStepEntity, Long> {

    List<BuildStepEntity> findByProjectIdOrderBySortOrderAsc(String projectId);

    void deleteByProjectId(String projectId);
}
