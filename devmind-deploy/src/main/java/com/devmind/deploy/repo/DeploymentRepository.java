package com.devmind.deploy.repo;

import com.devmind.deploy.model.DeploymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentRepository extends JpaRepository<DeploymentEntity, Long> {

    List<DeploymentEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<DeploymentEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    /** P0-6：按需求聚合部署（需求主线视图） */
    List<DeploymentEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：需求概览按工作单元集合聚合 */
    List<DeploymentEntity> findByWorkItemIdInOrderByCreatedAtDesc(java.util.Collection<String> workItemIds);

    /** FR-04 幂等：同 project + server + build 的进行中/已完成部署（识别重复部署） */
    List<DeploymentEntity> findByProjectIdAndServerIdAndBuildIdAndStatusIn(
            String projectId, Long serverId, Long buildId, List<String> statuses);
}
