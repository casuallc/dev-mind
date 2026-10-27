package com.devmind.usage.repo;

import com.devmind.session.model.SessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * CAP-67：会话用量只读聚合查询。过滤要素（时段按 createdAt 归属 + 创建人 + 模型）全可空；
 * 与 {@link UsageChatStatsRepository} 同形状，服务层合并两源。
 */
public interface UsageSessionStatsRepository extends JpaRepository<SessionEntity, String> {

    /** PG 无法推断孤立 `? is null` 参数位的类型（42P18，prepare 期即炸），
     * 可空筛选参数的 null 判断一律 cast（JpqlNullableParamCastTest 钉死全仓）。 */
    String FILTER = " where (cast(:start as timestamp) is null or e.createdAt >= :start)"
            + " and (cast(:end as timestamp) is null or e.createdAt < :end)"
            + " and (cast(:user as string) is null or e.createdBy = :user)"
            + " and (cast(:model as string) is null or e.model = :model)";

    String TOTALS_SELECT = "select new com.devmind.usage.repo.UsageTotals(count(e),"
            + " coalesce(sum(e.turnCount), 0), coalesce(sum(e.costUsd), 0),"
            + " coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e";

    String GROUP_SELECT = "select new com.devmind.usage.repo.UsageGroupRow";

    @Query(TOTALS_SELECT + FILTER)
    UsageTotals totals(@Param("start") Instant start, @Param("end") Instant end,
                       @Param("user") String user, @Param("model") String model);

    @Query(GROUP_SELECT + "(e.requirementId, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.requirementId")
    List<UsageGroupRow> groupByRequirement(@Param("start") Instant start, @Param("end") Instant end,
                                           @Param("user") String user, @Param("model") String model);

    @Query(GROUP_SELECT + "(e.projectId, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.projectId")
    List<UsageGroupRow> groupByProject(@Param("start") Instant start, @Param("end") Instant end,
                                       @Param("user") String user, @Param("model") String model);

    @Query(GROUP_SELECT + "(e.model, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.model")
    List<UsageGroupRow> groupByModel(@Param("start") Instant start, @Param("end") Instant end,
                                     @Param("user") String user, @Param("model") String model);

    @Query(GROUP_SELECT + "(e.createdBy, count(e), coalesce(sum(e.turnCount), 0),"
            + " coalesce(sum(e.costUsd), 0), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0))"
            + " from SessionEntity e" + FILTER + " group by e.createdBy")
    List<UsageGroupRow> groupByUser(@Param("start") Instant start, @Param("end") Instant end,
                                    @Param("user") String user, @Param("model") String model);

    @Query("select new com.devmind.usage.repo.UsageLiteRow(e.createdAt, e.costUsd, e.inputTokens,"
            + " e.outputTokens, e.turnCount) from SessionEntity e" + FILTER)
    List<UsageLiteRow> liteRows(@Param("start") Instant start, @Param("end") Instant end,
                                @Param("user") String user, @Param("model") String model);

    /** 排序键 = input+output+cacheRead（含缓存读的投入量），与展示列「总 tokens」=input+output
     * 口径刻意不同：缓存读占大头，按它排才能暴露重会话；勿为「统一口径」改回。 */
    @Query("select e from SessionEntity e" + FILTER
            + " order by coalesce(e.inputTokens, 0) + coalesce(e.outputTokens, 0)"
            + " + coalesce(e.cacheReadTokens, 0) desc, e.createdAt desc")
    List<SessionEntity> topByTokens(@Param("start") Instant start, @Param("end") Instant end,
                                    @Param("user") String user, @Param("model") String model,
                                    Pageable pageable);
}
