package com.devmind.test.repo;

import com.devmind.test.model.TestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunRepository extends JpaRepository<TestRunEntity, Long> {

    List<TestRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<TestRunEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    /** 普通运行历史不混入方案子运行（子运行经父方案运行下钻查看） */
    List<TestRunEntity> findByProjectIdAndPlanRunIdIsNullOrderByCreatedAtDesc(String projectId);

    List<TestRunEntity> findByProjectIdAndStatusAndPlanRunIdIsNullOrderByCreatedAtDesc(String projectId,
                                                                                       String status);

    List<TestRunEntity> findByPlanRunIdOrderByIdAsc(Long planRunId);

    List<TestRunEntity> findByPlanRunIdInOrderByIdAsc(java.util.Collection<Long> planRunIds);

    /** P0-6：按需求聚合测试运行（需求主线视图） */
    List<TestRunEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：需求概览按工作单元集合聚合 */
    List<TestRunEntity> findByWorkItemIdInOrderByCreatedAtDesc(java.util.Collection<String> workItemIds);

    /** 僵尸收割：指定状态集合且创建早于阈值（QUEUED/RUNNING 超时无终态） */
    List<TestRunEntity> findByStatusInAndCreatedAtBefore(java.util.Collection<String> statuses, java.time.Instant before);

    /** CAP-104 M3 统计：项目时间窗内的运行（含方案子运行） */
    List<TestRunEntity> findByProjectIdAndCreatedAtAfterOrderByCreatedAtAsc(String projectId, java.time.Instant after);


}
