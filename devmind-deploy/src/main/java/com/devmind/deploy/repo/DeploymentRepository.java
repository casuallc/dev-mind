package com.devmind.deploy.repo;

import com.devmind.deploy.model.DeploymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentRepository extends JpaRepository<DeploymentEntity, Long> {

    /** 部署历史分页（排序在 Pageable 里指定：createdAt DESC） */
    Page<DeploymentEntity> findByProjectId(String projectId, Pageable pageable);

    Page<DeploymentEntity> findByProjectIdAndStatus(String projectId, String status, Pageable pageable);

    /** P0-6：按需求聚合部署（需求主线视图） */
    List<DeploymentEntity> findByWorkItemIdOrderByCreatedAtDesc(String workItemId);

    /** CAP-13：需求概览按工作单元集合聚合 */
    List<DeploymentEntity> findByWorkItemIdInOrderByCreatedAtDesc(java.util.Collection<String> workItemIds);

    /** FR-04 幂等：同 project + node + build 的进行中/已完成部署（识别重复部署） */
    List<DeploymentEntity> findByProjectIdAndAgentNodeIdAndBuildIdAndStatusIn(
            String projectId, String agentNodeId, Long buildId, List<String> statuses);
}
