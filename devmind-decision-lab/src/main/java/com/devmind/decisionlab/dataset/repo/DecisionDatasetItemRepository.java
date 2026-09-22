package com.devmind.decisionlab.dataset.repo;

import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DecisionDatasetItemRepository extends JpaRepository<DecisionDatasetItemEntity, Long> {

    Page<DecisionDatasetItemEntity> findByDatasetIdOrderByIdAsc(Long datasetId, Pageable pageable);

    Page<DecisionDatasetItemEntity> findByDatasetIdAndCaseGroupOrderByIdAsc(Long datasetId, String caseGroup,
                                                                           Pageable pageable);

    /**
     * 整集全量取（冻结校验、覆盖统计、修版复制用）。
     *
     * <p>这里确实把整集的 state/questions/gold 都读进内存，是有意的：这些操作都是<b>人按一下
     * 才发生一次</b>的（冻结/看详情/修版），规模按 CAP-56 的设定是几十到几千条 × 每条几 KB。
     * 真要跑到十万条，该改的是"评测集该不该这么大"，而不是给这几个操作做流式——那会让
     * "对照组逐条校验"这种跨条判断变得难写且难读。</p>
     */
    List<DecisionDatasetItemEntity> findByDatasetIdOrderByIdAsc(Long datasetId);

    long countByDatasetId(Long datasetId);

    /** 各组条数一次查完（列表/详情要按组分列显示，逐组 count 是 N 次往返） */
    @Query("""
            select i.caseGroup, count(i) from DecisionDatasetItemEntity i
            where i.datasetId = :datasetId
            group by i.caseGroup
            """)
    List<Object[]> countByCaseGroup(@Param("datasetId") Long datasetId);

    /**
     * 集内出现过的题面版本及各自条数。冻结要求<b>恰好一种</b>：一个集里混着两个题面版本的话，
     * 指标的"分母"就不统一了——同一份报告里一半的题 A 题面、一半 B 题面，数字没意义。
     */
    @Query("""
            select i.questionSetVersion, count(i) from DecisionDatasetItemEntity i
            where i.datasetId = :datasetId
            group by i.questionSetVersion
            """)
    List<Object[]> countByQuestionSetVersion(@Param("datasetId") Long datasetId);

    void deleteByDatasetId(Long datasetId);
}
