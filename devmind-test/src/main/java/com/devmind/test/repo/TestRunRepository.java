package com.devmind.test.repo;

import com.devmind.test.model.TestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunRepository extends JpaRepository<TestRunEntity, Long> {

    List<TestRunEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<TestRunEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    /** P0-6：按需求聚合测试运行（需求主线视图） */
    List<TestRunEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：需求概览按工作单元集合聚合 */
    List<TestRunEntity> findByWorkItemIdInOrderByCreatedAtDesc(java.util.Collection<String> workItemIds);

    /** 僵尸收割：指定状态集合且创建早于阈值（QUEUED/RUNNING 超时无终态） */
    List<TestRunEntity> findByStatusInAndCreatedAtBefore(java.util.Collection<String> statuses, java.time.Instant before);


}
