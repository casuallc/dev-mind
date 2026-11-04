package com.devmind.security.repo;

import com.devmind.security.model.SecurityScanReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecurityScanReportRepository extends JpaRepository<SecurityScanReportEntity, Long> {

    List<SecurityScanReportEntity> findByScanId(Long scanId);

    Optional<SecurityScanReportEntity> findByScanIdAndEngineAndFormat(Long scanId, String engine, String format);
}
