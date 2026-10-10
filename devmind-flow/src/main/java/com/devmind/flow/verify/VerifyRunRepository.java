package com.devmind.flow.verify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VerifyRunRepository extends JpaRepository<VerifyRunEntity, Long> {

    /** 验证历史（需求详情页「验证」Tab） */
    List<VerifyRunEntity> findByRequirementIdOrderByCreatedAtDesc(String requirementId);

    /** 需求级互斥复查（事务内，配合应用层分段锁） */
    boolean existsByRequirementIdAndStatus(String requirementId, String status);

    /** 日配额计数：该项目当日已受理 run 数（含失败——受理即计） */
    long countByProjectIdAndCreatedAtGreaterThanEqual(String projectId, Instant since);

    /** 最近一次 SUCCESS（无新提交判定基准） */
    Optional<VerifyRunEntity> findFirstByRequirementIdAndStatusOrderByCreatedAtDesc(
            String requirementId, String status);

    /** 断连巡检：捞起超时仍 RUNNING 的僵尸 run */
    List<VerifyRunEntity> findByStatusAndCreatedAtBefore(String status, Instant before);
}
