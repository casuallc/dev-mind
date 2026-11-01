package com.devmind.test.repo;

import com.devmind.test.model.TestPlanItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestPlanItemRepository extends JpaRepository<TestPlanItemEntity, Long> {

    List<TestPlanItemEntity> findByPlanIdOrderBySeqAsc(Long planId);

    void deleteByPlanId(Long planId);
}
