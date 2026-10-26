package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogPlanItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorklogPlanItemRepository extends JpaRepository<WorklogPlanItemEntity, Long> {

    /** 某周期计划项（sort_order 同值按 id 升序=录入序） */
    List<WorklogPlanItemEntity> findByUserIdAndScopeAndPlanDateOrderBySortOrderAscIdAsc(
            String userId, String scope, LocalDate planDate);

    /** owner 强制：他人项查无（404 口径同条目，不暴露存在性） */
    Optional<WorklogPlanItemEntity> findByIdAndUserId(Long id, String userId);

    /** 指定周期内指定状态集的计划项（上一周期未完成提示用：TODO/DOING） */
    List<WorklogPlanItemEntity> findByUserIdAndScopeAndPlanDateAndStatusInOrderBySortOrderAscIdAsc(
            String userId, String scope, LocalDate planDate, Collection<String> statuses);

    /** 一段日期内的全部计划项（CAP-88 M2 复盘素材：DAY 项按日散落、WEEK 项落在周一，一段区间两者通吃） */
    List<WorklogPlanItemEntity> findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(
            String userId, LocalDate from, LocalDate to);

    /** 条目抽屉可关联项：当日日计划 + 当周周计划，排除 DROPPED/CARRIED（DOING/DONE 可选——补录忘挂的执行） */
    @Query("SELECT p FROM WorklogPlanItemEntity p WHERE p.userId = :userId"
            + " AND ((p.scope = 'DAY' AND p.planDate = :date) OR (p.scope = 'WEEK' AND p.planDate = :weekStart))"
            + " AND p.status IN ('TODO', 'DOING', 'DONE')"
            + " ORDER BY p.scope ASC, p.sortOrder ASC, p.id ASC")
    List<WorklogPlanItemEntity> findLinkable(@Param("userId") String userId,
                                             @Param("date") LocalDate date,
                                             @Param("weekStart") LocalDate weekStart);
}
