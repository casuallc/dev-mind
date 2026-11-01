package com.devmind.test.repo;

import com.devmind.test.model.TestPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestPlanRepository extends JpaRepository<TestPlanEntity, Long> {

    List<TestPlanEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    List<TestPlanEntity> findByProjectIdAndIsTemplateOrderByUpdatedAtDesc(String projectId, Boolean isTemplate);
}
