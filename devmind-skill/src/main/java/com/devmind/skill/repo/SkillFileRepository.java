package com.devmind.skill.repo;

import com.devmind.skill.model.SkillFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillFileRepository extends JpaRepository<SkillFileEntity, String> {

    List<SkillFileEntity> findBySkillIdOrderByPathAsc(String skillId);

    Optional<SkillFileEntity> findBySkillIdAndPath(String skillId, String path);

    boolean existsBySkillIdAndPath(String skillId, String path);

    void deleteBySkillId(String skillId);

    long countBySkillId(String skillId);

    @Query("select coalesce(sum(f.size), 0) from SkillFileEntity f where f.skillId = :skillId")
    long sumSizeBySkillId(@Param("skillId") String skillId);
}
