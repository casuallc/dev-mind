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

    long deleteByCreatedAtBefore(Instant cutoff);
}
