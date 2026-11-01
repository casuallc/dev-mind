package com.devmind.test.repo;

import com.devmind.test.model.TestPlanRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestPlanRunRepository extends JpaRepository<TestPlanRunEntity, Long> {

    List<TestPlanRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<TestPlanRunEntity> findByPlanIdOrderByCreatedAtDesc(Long planId);

    List<TestPlanRunEntity> findByStatusInAndCreatedAtBefore(java.util.Collection<String> statuses,
                                                             java.time.Instant before);

    /** M3 统计：按方案 + 时间窗取父运行，再下钻子运行 */
    List<TestPlanRunEntity> findByProjectIdAndPlanIdAndCreatedAtAfterOrderByCreatedAtAsc(
            String projectId, Long planId, java.time.Instant after);
}
