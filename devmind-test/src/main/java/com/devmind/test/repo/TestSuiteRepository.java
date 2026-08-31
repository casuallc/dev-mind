package com.devmind.test.repo;

import com.devmind.test.model.TestSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuiteEntity, Long> {

    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtAsc(String projectId);

    void deleteByProjectId(String projectId);
}
