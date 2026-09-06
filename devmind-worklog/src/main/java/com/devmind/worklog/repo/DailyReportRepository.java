package com.devmind.worklog.repo;

import com.devmind.worklog.model.DailyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReportEntity, Long> {

    Optional<DailyReportEntity> findByUserIdAndWorkDate(String userId, LocalDate workDate);

    List<DailyReportEntity> findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(
            String userId, LocalDate from, LocalDate to);

    /** 最近报告列表（新日期在前） */
    List<DailyReportEntity> findByUserIdAndWorkDateBetweenOrderByWorkDateDesc(
            String userId, LocalDate from, LocalDate to);

    Optional<DailyReportEntity> findByIdAndUserId(Long id, String userId);
}
