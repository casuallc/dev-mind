package com.devmind.worklog.repo;

import com.devmind.worklog.model.WeeklyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReportEntity, Long> {

    Optional<WeeklyReportEntity> findByUserIdAndWeekStart(String userId, LocalDate weekStart);

    Optional<WeeklyReportEntity> findByIdAndUserId(Long id, String userId);
}
