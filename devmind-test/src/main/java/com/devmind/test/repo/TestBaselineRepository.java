package com.devmind.test.repo;

import com.devmind.test.model.TestBaselineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestBaselineRepository extends JpaRepository<TestBaselineEntity, Long> {

    List<TestBaselineEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
