package com.devmind.usage.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * CAP-67：会话用量只读聚合查询。过滤三要素（时段按 createdAt 归属 + 创建人）全可空；
 * 与 {@link UsageChatStatsRepository} 同形状，服务层合并两源。
 */
public interface UsageSessionStatsRepository extends JpaRepository<SessionEntity, String> {

    /** PG 无法推断裸 'Instant 参数 is null' 的类型，时间可空筛选必须 cast（JpqlNullableParamCastTest 钉死）。 */
    String FILTER = " where (cast(:start as timestamp) is null or e.createdAt >= :start)"
            + " and (cast(:end as timestamp) is null or e.createdAt < :end)"
            + " and (:user is null or e.createdBy = :user)";

    String TOTALS_SELECT = "select new com.devmind.usage.repo.UsageTotals(count(e),"
            + " coalesce(sum(e.turnCount), 0), coalesce(sum(e.costUsd), 0),"
            + " coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e";

    String GROUP_SELECT = "select new com.devmind.usage.repo.UsageGroupRow";

    @Query(TOTALS_SELECT + FILTER)
    UsageTotals totals(@Param("start") Instant start, @Param("end") Instant end, @Param("user") String user);

    @Query(GROUP_SELECT + "(e.requirementId, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.requirementId")
    List<UsageGroupRow> groupByRequirement(@Param("start") Instant start, @Param("end") Instant end,
                                           @Param("user") String user);

    @Query(GROUP_SELECT + "(e.projectId, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.projectId")
    List<UsageGroupRow> groupByProject(@Param("start") Instant start, @Param("end") Instant end,
                                       @Param("user") String user);

    @Query(GROUP_SELECT + "(e.model, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.model")
    List<UsageGroupRow> groupByModel(@Param("start") Instant start, @Param("end") Instant end,
                                     @Param("user") String user);

    @Query(GROUP_SELECT + "(e.createdBy, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.createdBy")
    List<UsageGroupRow> groupByUser(@Param("start") Instant start, @Param("end") Instant end,
                                    @Param("user") String user);

    @Query("select new com.devmind.usage.repo.UsageLiteRow(e.createdAt, e.costUsd, e.inputTokens,"
            + " e.outputTokens, e.turnCount) from SessionEntity e" + FILTER)
    List<UsageLiteRow> liteRows(@Param("start") Instant start, @Param("end") Instant end,
                                @Param("user") String user);

    @Query("select e from SessionEntity e" + FILTER + " order by coalesce(e.costUsd, 0) desc")
    List<SessionEntity> topByCost(@Param("start") Instant start, @Param("end") Instant end,
                                  @Param("user") String user, Pageable pageable);
}
