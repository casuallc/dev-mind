package com.devmind.test.repo;

import com.devmind.test.model.TestCaseResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResultEntity, Long> {

    List<TestCaseResultEntity> findByRunIdOrderBySortAsc(Long runId);

    void deleteByRunId(Long runId);
}
