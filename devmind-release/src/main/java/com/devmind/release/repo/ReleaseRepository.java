package com.devmind.release.repo;

import com.devmind.release.model.ReleaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<ReleaseEntity, Long> {

    List<ReleaseEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<ReleaseEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    Optional<ReleaseEntity> findByProjectIdAndReleaseVersion(String projectId, String version);

    /** P0-6：按任务聚合发版记录（任务主线视图） */
    List<ReleaseEntity> findByTaskIdOrderByCreatedAtDesc(String taskId);

    Optional<ReleaseEntity> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);
}
