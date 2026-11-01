package com.devmind.test.repo;

import com.devmind.test.model.TestEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestEvidenceRepository extends JpaRepository<TestEvidenceEntity, Long> {

    List<TestEvidenceEntity> findByRunIdOrderBySeqNoAsc(Long runId);

    List<TestEvidenceEntity> findByResultIdOrderBySeqNoAsc(Long resultId);

    Optional<TestEvidenceEntity> findTopByRunIdOrderBySeqNoDesc(Long runId);

    void deleteByRunId(Long runId);
}
