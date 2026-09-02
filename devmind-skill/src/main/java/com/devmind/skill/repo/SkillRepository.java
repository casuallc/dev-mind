package com.devmind.skill.repo;

import com.devmind.skill.model.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SkillRepository extends JpaRepository<SkillEntity, String>, JpaSpecificationExecutor<SkillEntity> {

    /** 重名校验：GLOBAL 的 projectId 约定为 ""（见 SkillEntity 类注释） */
    boolean existsByScopeAndProjectIdAndName(String scope, String projectId, String name);

    /** 批量取附件数（列表页展示），返回 [skillId, count] 行 */
    @Query("select f.skillId, count(f) from SkillFileEntity f where f.skillId in :ids group by f.skillId")
    List<Object[]> countFilesBySkillIds(@Param("ids") List<String> ids);
}
