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
 * <p>时间可空参数必须 cast（JpqlNullableParamCastTest 钉死）；字符串参数裸 is null
 * 安全的前提是同一参数在查询另一半与字符串列比较（PG 才能推断 text），保持成对写法。</p>
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    /** PG 无法推断裸 'Instant 参数 is null' 的类型，时间可空筛选必须 cast（JpqlNullableParamCastTest 钉死）。 */
    String FILTER = " where (cast(:start as timestamp) is null or e.createdAt >= :start)"
            + " and (cast(:end as timestamp) is null or e.createdAt < :end)"
            + " and (:owner is null or e.owner = :owner)"
            + " and (:source is null or e.source = :source)"
            + " and (:model is null or e.model = :model)";

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
