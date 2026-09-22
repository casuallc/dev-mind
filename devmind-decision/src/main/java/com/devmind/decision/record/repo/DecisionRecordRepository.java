package com.devmind.decision.record.repo;

import com.devmind.decision.record.model.DecisionRecordEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecordEntity, Long> {

    /** upsert 的定位键（唯一约束同上）：同一实体只留一行 */
    Optional<DecisionRecordEntity> findByCapabilityAndSubjectId(String capability, String subjectId);

    /**
     * 记录页列表：capability/起始时间均可空（空 = 不限），按 id 倒序（新记录在上）。
     *
     * <p><b>可空参数的空值判断必须写成 {@code cast(:x as …) is null}</b>：裸的
     * {@code ? is null} 在 PG 上是"毫无上下文"的参数位——PG 只能靠上下文推断类型，
     * 推断不出来直接报 {@code could not determine data type of parameter $N}（整条查询
     * 在 prepare 阶段就死，与参数取值无关）。String/Integer 参数侥幸没事，是因为 JDBC
     * 驱动知道这些 Java 类型的 OID 会随 Parse 一起送；{@code Instant} 没有，所以"按时间筛选"
     * 这一个条件会把整页打成 500。cast 把类型写进 SQL，推断不再依赖驱动送什么。
     * H2/MySQL 不做这层校验 → 本地跑得起来不代表线上跑得起来（2026-09-22 224 环境实录）。</p>
     */
    @Query("""
            select r from DecisionRecordEntity r
            where (cast(:capability as string) is null or r.capability = :capability)
              and (cast(:since as timestamp) is null or r.createdAt >= :since)
            order by r.id desc
            """)
    Page<DecisionRecordEntity> search(@Param("capability") String capability,
                                      @Param("since") Instant since,
                                      Pageable pageable);

    /** 导出候选：三份快照齐全才可能是训练样本（缺 state/questions 的行是"只裁决过"的记录）。 */
    @Query("""
            select r from DecisionRecordEntity r
            where (cast(:capability as string) is null or r.capability = :capability)
              and (cast(:since as timestamp) is null or r.createdAt >= :since)
              and r.stateJson is not null and r.questionsJson is not null and r.goldJson is not null
            order by r.id asc
            """)
    List<DecisionRecordEntity> findForExport(@Param("capability") String capability,
                                            @Param("since") Instant since);

    /**
     * CAP-56 收编候选：<b>不做"能不能训"的筛选</b>，范围内一律取出。
     *
     * <p>与 {@link #findForExport} 的区别正是收编这件事的意义所在：导出只要"能用的"，
     * 收编还要能回答"<b>为什么这条不能用</b>"。SQL 里先把缺快照的行滤掉的话，
     * 页面上就只剩一句"可收编 3 条"，而那 200 条为什么不收永远说不出来
     * （见 {@code RecordIntake}）。筛掉不可用的行是调用方的事。</p>
     */
    @Query("""
            select r from DecisionRecordEntity r
            where (cast(:capability as string) is null or r.capability = :capability)
              and (cast(:since as timestamp) is null or r.createdAt >= :since)
            order by r.id asc
            """)
    Page<DecisionRecordEntity> findForIntake(@Param("capability") String capability,
                                            @Param("since") Instant since,
                                            Pageable pageable);
}
