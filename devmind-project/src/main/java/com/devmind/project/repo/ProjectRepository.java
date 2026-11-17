package com.devmind.project.repo;

import com.devmind.project.model.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    /**
     * CAP-109 FR-01 关键字搜索（status/kw 任一可空=不限）：kw 小写 like 匹配
     * name/description（CLOB 须 cast，红线）/path。cast(:kw as string) 防 PG bytea（照
     * SkillRepository 范式）。常量化供 H2 回归单测执行同一串，防实现与测试漂移。
     */
    String SEARCH_JPQL = "select p from ProjectEntity p where (cast(:status as string) is null or p.status = :status)"
            + " and (cast(:kw as string) is null"
            + "     or lower(p.name) like lower(concat('%', cast(:kw as string), '%'))"
            + "     or lower(cast(p.description as string)) like lower(concat('%', cast(:kw as string), '%'))"
            + "     or lower(p.path) like lower(concat('%', cast(:kw as string), '%')))"
            + " order by p.createdAt desc";

    List<ProjectEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ProjectEntity> findAllByOrderByCreatedAtDesc();

    @Query(SEARCH_JPQL)
    List<ProjectEntity> search(@Param("status") String status, @Param("kw") String keyword);

    long countByPath(String path);

    /** CAP-41：按种类 + 归属用户查（WORKLOG 项目每用户至多一个） */
    java.util.Optional<ProjectEntity> findByKindAndOwnerId(String kind, String ownerId);
}
