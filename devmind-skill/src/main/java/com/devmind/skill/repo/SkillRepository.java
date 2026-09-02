package com.devmind.skill.repo;

import com.devmind.skill.model.SkillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SkillRepository extends JpaRepository<SkillEntity, String> {

    /** 组合过滤（null=不限）。GLOBAL 行 projectId=""，故 projectId 精确匹配天然不混入 GLOBAL 行 */
    @Query("select s from SkillEntity s where (:scope is null or s.scope = :scope)"
            + " and (:projectId is null or s.projectId = :projectId)"
            + " and (:status is null or s.status = :status)"
            + " and (:kw is null or lower(s.name) like lower(concat('%', :kw, '%'))"
            + "     or lower(s.description) like lower(concat('%', :kw, '%')))")
    Page<SkillEntity> search(@Param("scope") String scope,
                             @Param("projectId") String projectId,
                             @Param("status") String status,
                             @Param("kw") String keyword,
                             Pageable pageable);

    /** 重名校验：GLOBAL 的 projectId 约定为 ""（见 SkillEntity 类注释） */
    boolean existsByScopeAndProjectIdAndName(String scope, String projectId, String name);

    /** 批量取附件数（列表页展示），返回 [skillId, count] 行 */
    @Query("select f.skillId, count(f) from SkillFileEntity f where f.skillId in :ids group by f.skillId")
    List<Object[]> countFilesBySkillIds(@Param("ids") List<String> ids);
}
