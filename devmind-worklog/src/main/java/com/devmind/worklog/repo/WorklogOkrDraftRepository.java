package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogOkrDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorklogOkrDraftRepository extends JpaRepository<WorklogOkrDraftEntity, Long> {

    /** owner 强制：他人草稿查无（404 口径同目标域） */
    Optional<WorklogOkrDraftEntity> findByIdAndUserId(Long id, String userId);

    /** 每人每周期唯一草稿（upsert 寻址；period 已含 YEAR/QUARTER 区分度） */
    Optional<WorklogOkrDraftEntity> findByUserIdAndPeriod(String userId, String period);
}
