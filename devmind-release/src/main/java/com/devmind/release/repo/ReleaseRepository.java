package com.devmind.release.repo;

import com.devmind.release.model.ReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<ReleaseEntity, Long> {

    List<ReleaseEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<ReleaseEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    Optional<ReleaseEntity> findByProjectIdAndReleaseVersion(String projectId, String version);

    Optional<ReleaseEntity> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);
}
