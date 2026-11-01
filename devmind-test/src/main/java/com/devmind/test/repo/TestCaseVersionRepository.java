package com.devmind.test.repo;

import com.devmind.test.model.TestCaseVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestCaseVersionRepository extends JpaRepository<TestCaseVersionEntity, Long> {

    List<TestCaseVersionEntity> findByCaseIdOrderByVersionNoDesc(Long caseId);

    Optional<TestCaseVersionEntity> findByCaseIdAndVersionNo(Long caseId, Integer versionNo);

    void deleteByCaseId(Long caseId);
}
