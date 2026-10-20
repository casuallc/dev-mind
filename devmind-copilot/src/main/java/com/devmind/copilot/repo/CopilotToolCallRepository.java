package com.devmind.copilot.repo;

import com.devmind.copilot.model.CopilotToolCallEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopilotToolCallRepository extends JpaRepository<CopilotToolCallEntity, Long> {

    List<CopilotToolCallEntity> findByTurnIdOrderByIdAsc(String turnId);

    List<CopilotToolCallEntity> findByActionIdOrderByIdAsc(String actionId);

    /** 计划步骤审计行（M8，FR-36）：step_seq 非空的行按步骤序号升序。 */
    List<CopilotToolCallEntity> findByActionIdAndStepSeqNotNullOrderByStepSeqAsc(String actionId);

    /** 计划已完成步骤数（进度展示）。 */
    long countByActionIdAndStepSeqNotNullAndStatus(String actionId, String status);

    long countByStatus(String status);

    /** 确认单取消/过期时，把关联的 PENDING_CONFIRM 行同步终结（FR-14）。 */
    @Modifying
    @Query("update CopilotToolCallEntity c set c.status = :status where c.actionId = :actionId "
            + "and c.status = 'PENDING_CONFIRM'")
    int closePendingByActionId(@Param("actionId") String actionId, @Param("status") String status);

    /** 清扫时批量终结已过期确认单的 PENDING_CONFIRM 行。 */
    @Modifying
    @Query("update CopilotToolCallEntity c set c.status = 'EXPIRED' where c.status = 'PENDING_CONFIRM' "
            + "and c.actionId in (select a.id from CopilotActionEntity a where a.status = 'EXPIRED')")
    int closeExpiredActions();

    /**
     * FR-36 兜底：被 sweep 判中断的计划，其 RUNNING/WAITING 步骤行同步判 FAILED
     * （以 createdAt 超计划总预算为准，避免误伤仍在执行中的计划步骤）。
     */
    @Modifying
    @Query("update CopilotToolCallEntity c set c.status = 'FAILED', c.error = '执行中断' "
            + "where c.status in ('RUNNING', 'WAITING') and c.createdAt <= :cutoff")
    int failStaleSteps(@Param("cutoff") Instant cutoff);

    long deleteByCreatedAtBefore(Instant cutoff);
}
