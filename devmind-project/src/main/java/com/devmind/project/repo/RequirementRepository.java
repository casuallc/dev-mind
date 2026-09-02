package com.devmind.project.repo;

import com.devmind.project.model.RequirementEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RequirementRepository extends JpaRepository<RequirementEntity, String> {

    List<RequirementEntity> findByProjectIdOrderBySeqDesc(String projectId);

    List<RequirementEntity> findByProjectIdAndStatusOrderBySeqDesc(String projectId, String status);

    /**
     * 组合过滤搜索：status/type/source 可空（null=不限）；keyword 匹配 title/externalKey
     * （不匹配 description——@Lob 上 LIKE 跨库不稳且无索引意义）。
     */
    @Query("select r from RequirementEntity r where r.projectId = :projectId"
            + " and (:status is null or r.status = :status)"
            + " and (:type is null or r.type = :type)"
            + " and (:source is null or r.source = :source)"
            + " and (:kw is null or lower(r.title) like lower(concat('%', :kw, '%'))"
            + "      or lower(r.externalKey) like lower(concat('%', :kw, '%')))")
    Page<RequirementEntity> search(@Param("projectId") String projectId,
                                   @Param("status") String status,
                                   @Param("type") String type,
                                   @Param("source") String source,
                                   @Param("kw") String keyword,
                                   Pageable pageable);

    /** 项目内当前最大 seq（无需求时 null） */
    @Query("select max(r.seq) from RequirementEntity r where r.projectId = :projectId")
    Long findMaxSeqByProjectId(@Param("projectId") String projectId);

    void deleteByProjectId(String projectId);
}
