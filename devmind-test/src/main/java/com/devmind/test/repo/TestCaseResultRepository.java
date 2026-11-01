package com.devmind.test.repo;

import com.devmind.test.model.TestCaseResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, Long> {

    List<TestCaseResultEntity> findByRunIdOrderBySortAsc(Long runId);

    /** CAP-104 M3 统计：批量取时间窗内结果，避免逐运行查询 */
    List<TestCaseResultEntity> findByRunIdInOrderByCreatedAtAsc(java.util.Collection<Long> runIds);

    void deleteByRunId(Long runId);
}
