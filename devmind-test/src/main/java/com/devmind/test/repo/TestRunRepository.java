package com.devmind.test.repo;

import com.devmind.test.model.TestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunRepository extends JpaRepository<TestRunEntity, Long> {

    List<TestRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<TestRunEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);
}
