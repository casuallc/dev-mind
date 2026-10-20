package com.devmind.copilot.repo;

import com.devmind.copilot.model.CopilotActionEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopilotActionRepository
        extends JpaRepository<CopilotActionEntity, String>, JpaSpecificationExecutor<CopilotActionEntity> {

    List<CopilotActionEntity> findByChannelAndExternalChatIdAndSenderIdAndStatusOrderByCreatedAtAsc(
            String channel, String externalChatId, String senderId, String status);

    List<CopilotActionEntity> findByTurnIdOrderByCreatedAtAsc(String turnId);

    Optional<CopilotActionEntity> findFirstByChannelAndExternalChatIdAndSenderIdAndShortCodeAndStatus(
            String channel, String externalChatId, String senderId, String shortCode, String status);

    /** 任意状态查询：用于把「不存在」区分为「已过期/已执行/已取消」。 */
    Optional<CopilotActionEntity> findFirstByChannelAndExternalChatIdAndSenderIdAndShortCodeOrderByCreatedAtDesc(
            String channel, String externalChatId, String senderId, String shortCode);

    /** 前缀（如 A7K2）不命中本身；只用于提示"请使用完整短码"。 */
    List<CopilotActionEntity> findByChannelAndExternalChatIdAndSenderIdAndStatusAndShortCodeStartingWithOrderByShortCodeAsc(
            String channel, String externalChatId, String senderId, String status, String shortCodePrefix);

    long countByStatus(String status);

    long countByStatusAndExpiresAtAfter(String status, Instant now);

    /** FR-14 原子抢占：影响行数 0 = 已执行/已过期/已取消。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CopilotActionEntity a set a.status = 'EXECUTING', a.executedAt = :now "
            + "where a.id = :id and a.status = 'PENDING' and a.expiresAt > :now")
    int claim(@Param("id") String id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CopilotActionEntity a set a.status = 'CANCELLED', a.executedAt = :now, a.error = :error "
            + "where a.id = :id and a.status = 'PENDING'")
    int cancelPending(@Param("id") String id, @Param("now") Instant now, @Param("error") String error);

    /** 分钟级清扫：PENDING → EXPIRED。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CopilotActionEntity a set a.status = 'EXPIRED' "
            + "where a.status = 'PENDING' and a.expiresAt <= :now")
    int expirePending(@Param("now") Instant now);

    /** 进程中断遗留的 EXECUTING → FAILED（不自动重试）。M8 起计划单分流到更长阈值，见下。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CopilotActionEntity a set a.status = 'FAILED', a.error = :error "
            + "where a.status = 'EXECUTING' and a.executedAt <= :cutoff and a.toolName <> :planTool")
    int failInterrupted(@Param("cutoff") Instant cutoff, @Param("error") String error,
                        @Param("planTool") String planTool);

    /** 异步计划（submit_plan）的中断收口：阈值用 plan-execution-timeout-minutes（FR-34）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CopilotActionEntity a set a.status = 'FAILED', a.error = :error "
            + "where a.status = 'EXECUTING' and a.executedAt <= :cutoff and a.toolName = :planTool")
    int failInterruptedPlans(@Param("cutoff") Instant cutoff, @Param("error") String error,
                             @Param("planTool") String planTool);

    long deleteByStatusInAndCreatedAtBefore(Collection<String> statuses, Instant cutoff);
}
