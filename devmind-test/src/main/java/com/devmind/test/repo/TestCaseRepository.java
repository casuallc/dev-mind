package com.devmind.test.repo;

import com.devmind.test.model.TestCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TestCaseRepository extends JpaRepository<TestCaseEntity, Long> {

    List<TestCaseEntity> findBySuiteIdOrderBySortAsc(Long suiteId);

    void deleteBySuiteId(Long suiteId);

    void deleteByIdIn(Collection<Long> ids);
}
