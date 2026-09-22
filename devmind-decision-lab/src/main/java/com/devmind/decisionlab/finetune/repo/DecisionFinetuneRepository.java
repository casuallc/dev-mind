package com.devmind.decisionlab.finetune.repo;

import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CAP-56 微调任务仓储。
 *
 * <p>筛选走派生方法而不是 JPQL 拼可空参——理由同 {@code DecisionEvaluationRepository}
 * （PG 上同一个参数被当成 bigint 与 varchar 用过就会报"类型不存在"，派生方法由 Hibernate
 * 生成带类型的绑定参数，从形状上避坑）。</p>
 */
public interface DecisionFinetuneRepository extends JpaRepository<DecisionFinetuneEntity, Long> {

    List<DecisionFinetuneEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<DecisionFinetuneEntity> findByDatasetIdOrderByIdDesc(Long datasetId, Pageable pageable);

    List<DecisionFinetuneEntity> findByBaseCheckpointIdOrderByIdDesc(Long baseCheckpointId, Pageable pageable);

    List<DecisionFinetuneEntity> findByCheckpointIdOrderByIdAsc(Long checkpointId);

    long countByDatasetId(Long datasetId);

    long countByBaseCheckpointId(Long baseCheckpointId);

    /** 运行中/排队中的数量（与评测共用一个并发闸门，见 {@code LabConcurrency}） */
    long countByStatusIn(List<String> statuses);
}
