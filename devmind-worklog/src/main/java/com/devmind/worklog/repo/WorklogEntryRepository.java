package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorklogEntryRepository extends JpaRepository<WorklogEntryEntity, String> {

    /** 范围条目（日期升序、日内按创建时间）；from/to 可空（null=不限），生成素材/判重扫描用。
     *  PG 红线：禁写 (:from IS NULL OR e.workDate >= :from)——孤立出现的 `? IS NULL` 无列可推断
     *  参数类型，PG 报 42P18（H2 测不出）；改 COALESCE(:from, e.workDate) 借列类型兜底。 */
    @Query("SELECT e FROM WorklogEntryEntity e WHERE e.userId = :userId"
            + " AND e.workDate >= COALESCE(:from, e.workDate) AND e.workDate <= COALESCE(:to, e.workDate)"
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
     *  cast 红线：keyword 只在 concat 内无列可推断，PG 会落成 bytea——必须 cast 为 string。
     *  CAP-90 FR-04：工时统计一律只取 status=DONE 事实条目。 */
    @Query("SELECT COALESCE(SUM(e.minutes), 0) FROM WorklogEntryEntity e"
            + " WHERE e.userId = :userId AND e.workDate BETWEEN :from AND :to"
            + " AND e.status = 'DONE'"
            + " AND (:keyword = '' OR LOWER(e.title) LIKE LOWER(CONCAT('%', cast(:keyword as string), '%')))")
    long sumMinutes(@Param("userId") String userId,
                    @Param("from") LocalDate from, @Param("to") LocalDate to,
                    @Param("keyword") String keyword);

    List<WorklogEntryEntity> findByUserIdAndWorkDateOrderByCreatedAtAsc(String userId, LocalDate workDate);

    /** CAP-90：当日指定状态集条目（DONE=日报素材；TODO/DOING=日滚转提示与计划对照） */
    List<WorklogEntryEntity> findByUserIdAndWorkDateAndStatusInOrderByCreatedAtAsc(
            String userId, LocalDate workDate, Collection<String> statuses);

    Optional<WorklogEntryEntity> findByIdAndUserId(String id, String userId);

    /** CAP-88 FR-04：某计划项的关联条目（删计划项时清引用用） */
    List<WorklogEntryEntity> findByPlanItemId(Long planItemId);

    /** CAP-88 M2 复盘素材：批量取一组计划项的关联条目（避免逐条 N+1） */
    List<WorklogEntryEntity> findByPlanItemIdIn(Collection<Long> planItemIds);

    /** git 导入幂等判定 */
    boolean existsByUserIdAndRepoIdAndCommitSha(String userId, Long repoId, String commitSha);

    /** 已导入判重集合的数据源（只取 GIT 关联两列，不载全行） */
    @Query("SELECT e.repoId, e.commitSha FROM WorklogEntryEntity e"
            + " WHERE e.userId = :userId AND e.repoId IS NOT NULL AND e.commitSha IS NOT NULL")
    List<Object[]> findGitKeys(@Param("userId") String userId);
}
