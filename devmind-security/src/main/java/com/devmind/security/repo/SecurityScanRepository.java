package com.devmind.security.repo;

import com.devmind.security.model.SecurityScanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecurityScanRepository extends JpaRepository<SecurityScanEntity, Long> {

    Page<SecurityScanEntity> findByProjectIdOrderByIdDesc(String projectId, Pageable pageable);

    List<SecurityScanEntity> findByProjectIdAndRepoIdAndStatusOrderByIdDesc(
            String projectId, Long repoId, String status);

    boolean existsByProjectIdAndRepoIdAndBranchAndStatusIn(
            String projectId, Long repoId, String branch, List<String> statuses);
}
