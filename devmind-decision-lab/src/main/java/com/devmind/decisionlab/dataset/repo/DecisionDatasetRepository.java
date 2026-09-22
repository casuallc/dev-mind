package com.devmind.decisionlab.dataset.repo;

import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DecisionDatasetRepository extends JpaRepository<DecisionDatasetEntity, Long> {

    /**
     * 列表：{@code kind} 可空（空 = 全部），按 id 倒序（新建的在上）。
     *
     * <p>可空参数写 {@code cast(:kind as string) is null} 是项目红线（见
     * {@code DecisionRecordRepository.search} 的注释）：裸 {@code ? is null} 在 PG 上是
     * "没有上下文的参数位"，类型推断不出来直接报 could not determine data type。
     * H2 不做这层校验，本地绿不代表线上绿。</p>
     */
    @Query("""
            select d from DecisionDatasetEntity d
            where (cast(:kind as string) is null or d.kind = :kind)
            order by d.id desc
            """)
    Page<DecisionDatasetEntity> search(@Param("kind") String kind, Pageable pageable);

    /** 同名各版本（修订链：新版本 = 同名 + version+1） */
    List<DecisionDatasetEntity> findByNameOrderByVersionDesc(String name);

    boolean existsByNameAndVersion(String name, int version);

    /** 已冻结的集数（闸门/概览用：有冻结集才谈得上评测） */
    long countByFrozenTrue();
}
