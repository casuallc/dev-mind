package com.devmind.usage.repo;

import com.devmind.chat.model.ChatSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * CAP-67：问答用量只读聚合查询（与 {@link UsageSessionStatsRepository} 同形状）。
 * 问答无 project/requirement 归属——需求/项目维度由服务层并入「未归属（问答）」桶，
 * 本接口只提供 model/user 两个两源共有的分组。
 */
public interface UsageChatStatsRepository extends JpaRepository<ChatSessionEntity, String> {

    /** PG 无法推断裸 'Instant 参数 is null' 的类型，时间可空筛选必须 cast（JpqlNullableParamCastTest 钉死）。 */
    String FILTER = " where (cast(:start as timestamp) is null or e.createdAt >= :start)"
            + " and (cast(:end as timestamp) is null or e.createdAt < :end)"
            + " and (:user is null or e.createdBy = :user)";

    String AGGS = "count(e), coalesce(sum(e.turnCount), 0), coalesce(sum(e.costUsd), 0),"
            + " coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0),"
            + " coalesce(sum(e.cacheReadTokens), 0), coalesce(sum(e.cacheCreationTokens), 0)";

    @Query("select new com.devmind.usage.repo.UsageTotals(" + AGGS + ") from ChatSessionEntity e" + FILTER)
    UsageTotals totals(@Param("start") Instant start, @Param("end") Instant end, @Param("user") String user);

    @Query("select new com.devmind.usage.repo.UsageGroupRow(e.model, " + AGGS + ")"
            + " from ChatSessionEntity e" + FILTER + " group by e.model")
    List<UsageGroupRow> groupByModel(@Param("start") Instant start, @Param("end") Instant end,
                                     @Param("user") String user);

    @Query("select new com.devmind.usage.repo.UsageGroupRow(e.createdBy, " + AGGS + ")"
            + " from ChatSessionEntity e" + FILTER + " group by e.createdBy")
    List<UsageGroupRow> groupByUser(@Param("start") Instant start, @Param("end") Instant end,
                                    @Param("user") String user);

    @Query("select new com.devmind.usage.repo.UsageLiteRow(e.createdAt, e.costUsd, e.inputTokens,"
            + " e.outputTokens, e.turnCount) from ChatSessionEntity e" + FILTER)
    List<UsageLiteRow> liteRows(@Param("start") Instant start, @Param("end") Instant end,
                                @Param("user") String user);

    @Query("select e from ChatSessionEntity e" + FILTER + " order by coalesce(e.costUsd, 0) desc")
    List<ChatSessionEntity> topByCost(@Param("start") Instant start, @Param("end") Instant end,
                                      @Param("user") String user, Pageable pageable);
}
