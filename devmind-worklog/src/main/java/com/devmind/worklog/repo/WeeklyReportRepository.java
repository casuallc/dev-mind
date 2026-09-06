package com.devmind.worklog.repo;

import com.devmind.worklog.model.WeeklyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReportEntity, Long> {

    Optional<WeeklyReportEntity> findByUserIdAndWeekStart(String userId, LocalDate weekStart);

    /** 最近周报列表（新周在前） */
    List<WeeklyReportEntity> findByUserIdAndWeekStartBetweenOrderByWeekStartDesc(
            String userId, LocalDate from, LocalDate to);

    Optional<WeeklyReportEntity> findByIdAndUserId(Long id, String userId);
}
