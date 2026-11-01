package com.devmind.test.repo;

import com.devmind.test.model.TestModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestModuleRepository extends JpaRepository<TestModuleEntity, Long> {

    List<TestModuleEntity> findByProjectIdOrderBySortNoAscNameAsc(String projectId);

    Optional<TestModuleEntity> findByProjectIdAndName(String projectId, String name);

    boolean existsByProjectIdAndName(String projectId, String name);

    long countByProjectId(String projectId);
}
