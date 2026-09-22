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

    /** 记录页列表：capability/起始时间均可空（空 = 不限），按 id 倒序（新记录在上）。 */
    @Query("""
            select r from DecisionRecordEntity r
            where (:capability is null or r.capability = :capability)
              and (:since is null or r.createdAt >= :since)
            order by r.id desc
            """)
    Page<DecisionRecordEntity> search(@Param("capability") String capability,
                                      @Param("since") Instant since,
                                      Pageable pageable);

    /** 导出候选：三份快照齐全才可能是训练样本（缺 state/questions 的行是"只裁决过"的记录）。 */
    @Query("""
            select r from DecisionRecordEntity r
            where (:capability is null or r.capability = :capability)
              and (:since is null or r.createdAt >= :since)
              and r.stateJson is not null and r.questionsJson is not null and r.goldJson is not null
            order by r.id asc
            """)
    List<DecisionRecordEntity> findForExport(@Param("capability") String capability,
                                            @Param("since") Instant since);
}
