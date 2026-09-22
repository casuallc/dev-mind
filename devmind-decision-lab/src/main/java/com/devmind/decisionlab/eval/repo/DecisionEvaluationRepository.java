package com.devmind.decisionlab.eval.repo;

import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CAP-56 评测运行仓储。
 *
 * <p><b>两个筛选维度用派生方法而不是 JPQL 拼可空参</b>：本仓库红线是"可空参数必须
 * {@code cast(:x as …) is null}"（PG 推不出裸参类型），而 {@code cast(:x as string)} 与
 * {@code e.datasetId = :x}（bigint）同时使用同一个参数时，Hibernate 按首次出现把参数绑成
 * 字符串，PG 上就是 {@code bigint = character varying} 不存在。派生方法由 Hibernate 生成
 * 带类型的绑定参数，从形状上就不会踩。四次组合（无筛 / 按集 / 按 checkpoint / 两个都按）
 * 各自成方法，也就不存在"裸参"这回事。</p>
 */
public interface DecisionEvaluationRepository extends JpaRepository<DecisionEvaluationEntity, Long> {

    List<DecisionEvaluationEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<DecisionEvaluationEntity> findByDatasetIdOrderByIdDesc(Long datasetId, Pageable pageable);

    List<DecisionEvaluationEntity> findByCheckpointIdOrderByIdDesc(Long checkpointId, Pageable pageable);

    List<DecisionEvaluationEntity> findByDatasetIdAndCheckpointIdOrderByIdDesc(Long datasetId, Long checkpointId,
                                                                              Pageable pageable);

    long countByDatasetId(Long datasetId);

    long countByCheckpointId(Long checkpointId);

    long countByDatasetIdAndCheckpointId(Long datasetId, Long checkpointId);

    /** 运行中/排队中的数量（触发时的并发闸门） */
    long countByStatusIn(List<String> statuses);
}
