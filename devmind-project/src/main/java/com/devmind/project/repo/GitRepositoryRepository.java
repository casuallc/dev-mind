package com.devmind.project.repo;

import com.devmind.project.model.GitRepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** CAP-29 全局代码仓库登记。 */
public interface GitRepositoryRepository extends JpaRepository<GitRepositoryEntity, Long> {

    /**
     * CAP-109 FR-02 列表搜索（status/kw 任一可空=不限）：kw 小写 like 匹配
     * name/remoteUrl/localPath；status 等值过滤。cast(:kw as string) 防 PG bytea。
     * 常量化供 H2 回归单测执行同一串。
     */
    String SEARCH_JPQL = "select r from GitRepositoryEntity r"
            + " where (cast(:status as string) is null or r.status = :status)"
            + " and (cast(:kw as string) is null"
            + "     or lower(r.name) like lower(concat('%', cast(:kw as string), '%'))"
            + "     or lower(r.remoteUrl) like lower(concat('%', cast(:kw as string), '%'))"
            + "     or lower(r.localPath) like lower(concat('%', cast(:kw as string), '%')))"
            + " order by r.createdAt desc";

    @Query(SEARCH_JPQL)
    List<GitRepositoryEntity> search(@Param("status") String status, @Param("kw") String keyword);

    Optional<GitRepositoryEntity> findByLocalPath(String localPath);

    Optional<GitRepositoryEntity> findByRemoteUrlKey(String remoteUrlKey);

    /** 定时 fetch 覆盖范围：服务端克隆 + 克隆就绪 + 启用 */
    List<GitRepositoryEntity> findBySourceTypeAndCloneStatusAndStatus(
            String sourceType, String cloneStatus, String status);
}
