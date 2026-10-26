package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogQuarterlyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorklogQuarterlyReportRepository extends JpaRepository<WorklogQuarterlyReportEntity, Long> {

    Optional<WorklogQuarterlyReportEntity> findByUserIdAndQuarter(String userId, String quarter);

    boolean existsByUserIdAndQuarter(String userId, String quarter);
}
