package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogGoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorklogGoalRepository extends JpaRepository<WorklogGoalEntity, Long> {

    /** owner 强制：他人目标查无（404 口径同条目/计划项，不暴露存在性） */
    Optional<WorklogGoalEntity> findByIdAndUserId(Long id, String userId);

    /** 一批周期的全部目标（tree 视图：年份 + 四季周期一次取回） */
    List<WorklogGoalEntity> findByUserIdAndPeriodInOrderBySortOrderAscIdAsc(
            String userId, Collection<String> periods);

    /** 某周期某类目标（linkable-krs：当季 KEY_RESULT 下拉） */
    List<WorklogGoalEntity> findByUserIdAndScopeAndPeriodAndKindAndStatusOrderBySortOrderAscIdAsc(
            String userId, String scope, String period, String kind, String status);

    /** 子项列表（删除 O 前置检查：有子项 400） */
    List<WorklogGoalEntity> findByUserIdAndParentId(String userId, Long parentId);
}
