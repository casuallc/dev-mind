package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorklogEntryRepository extends JpaRepository<WorklogEntryEntity, String> {

    /** 范围条目（日期升序、日内按创建时间）；from/to 可空（null=不限），生成素材/判重扫描用 */
    @Query("SELECT e FROM WorklogEntryEntity e WHERE e.userId = :userId"
            + " AND (:from IS NULL OR e.workDate >= :from) AND (:to IS NULL OR e.workDate <= :to)"
            + " ORDER BY e.workDate ASC, e.createdAt ASC")
    List<WorklogEntryEntity> findRange(@Param("userId") String userId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 分页列表（排序由 Pageable 传入：workDate desc, createdAt desc 最新在前） */
    Page<WorklogEntryEntity> findByUserIdAndWorkDateBetween(
            String userId, LocalDate from, LocalDate to, Pageable pageable);

    /** 分页列表 + 标题模糊匹配（title 为普通 varchar，非 CLOB，IgnoreCase 安全） */
    Page<WorklogEntryEntity> findByUserIdAndWorkDateBetweenAndTitleContainingIgnoreCase(
            String userId, LocalDate from, LocalDate to, String keyword, Pageable pageable);

    /** 范围内工时合计（分钟）；keyword 为空串时不过滤标题。
     *  cast 红线：keyword 只在 concat 内无列可推断，PG 会落成 bytea——必须 cast 为 string。 */
    @Query("SELECT COALESCE(SUM(e.minutes), 0) FROM WorklogEntryEntity e"
            + " WHERE e.userId = :userId AND e.workDate BETWEEN :from AND :to"
            + " AND (:keyword = '' OR LOWER(e.title) LIKE LOWER(CONCAT('%', cast(:keyword as string), '%')))")
    long sumMinutes(@Param("userId") String userId,
                    @Param("from") LocalDate from, @Param("to") LocalDate to,
                    @Param("keyword") String keyword);

    List<WorklogEntryEntity> findByUserIdAndWorkDateOrderByCreatedAtAsc(String userId, LocalDate workDate);

    Optional<WorklogEntryEntity> findByIdAndUserId(String id, String userId);

    /** git 导入幂等判定 */
    boolean existsByUserIdAndRepoIdAndCommitSha(String userId, Long repoId, String commitSha);

    /** 已导入判重集合的数据源（只取 GIT 关联两列，不载全行） */
    @Query("SELECT e.repoId, e.commitSha FROM WorklogEntryEntity e"
            + " WHERE e.userId = :userId AND e.repoId IS NOT NULL AND e.commitSha IS NOT NULL")
    List<Object[]> findGitKeys(@Param("userId") String userId);
}
