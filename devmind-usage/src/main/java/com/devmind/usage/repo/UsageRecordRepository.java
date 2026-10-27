package com.devmind.usage.repo;

import com.devmind.usage.model.UsageRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 逐回合用量账本仓储。写入由 {@code UsageRecordService}（UsageRecordSink 实现）发起；
 * 本接口的只读查询供请求日志分页与按小时分桶。
 *
 * <p>PG 无法推断孤立 `? is null` 参数位的类型（42P18），可空筛选参数的 null 判断
 * 一律 cast（JpqlNullableParamCastTest 钉死全仓）。</p>
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    /** 可空筛选参数 null 判断一律 cast（PG 42P18 红线，见类注释）。 */
    String FILTER = " where (cast(:start as timestamp) is null or e.createdAt >= :start)"
            + " and (cast(:end as timestamp) is null or e.createdAt < :end)"
            + " and (cast(:owner as string) is null or e.owner = :owner)"
            + " and (cast(:source as string) is null or e.source = :source)"
            + " and (cast(:model as string) is null or e.model = :model)";

    /** 请求日志分页：时间倒序，id 做同秒 tiebreaker（翻页稳定）。 */
    @Query("select e from UsageRecordEntity e" + FILTER + " order by e.createdAt desc, e.id desc")
    List<UsageRecordEntity> pageMatching(@Param("start") Instant start, @Param("end") Instant end,
                                         @Param("owner") String owner, @Param("source") String source,
                                         @Param("model") String model, Pageable pageable);

    @Query("select count(e) from UsageRecordEntity e" + FILTER)
    long countMatching(@Param("start") Instant start, @Param("end") Instant end,
                       @Param("owner") String owner, @Param("source") String source,
                       @Param("model") String model);

    @Query("select new com.devmind.usage.repo.UsageRecordHourlyRow(e.createdAt, e.inputTokens, e.outputTokens)"
            + " from UsageRecordEntity e" + FILTER)
    List<UsageRecordHourlyRow> hourlyRows(@Param("start") Instant start, @Param("end") Instant end,
                                          @Param("owner") String owner, @Param("source") String source,
                                          @Param("model") String model);
}
