package com.devmind.security.repo;

import com.devmind.security.model.SecuritySuppressionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecuritySuppressionRepository extends JpaRepository<SecuritySuppressionEntity, Long> {

    List<SecuritySuppressionEntity> findByProjectId(String projectId);

    List<SecuritySuppressionEntity> findByProjectIdAndRepoId(String projectId, Long repoId);

    Optional<SecuritySuppressionEntity> findByProjectIdAndRepoIdAndEngineAndFingerprint(
            String projectId, Long repoId, String engine, String fingerprint);
}
