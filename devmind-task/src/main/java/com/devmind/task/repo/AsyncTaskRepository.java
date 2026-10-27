package com.devmind.task.repo;

import com.devmind.task.model.AsyncTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AsyncTaskRepository extends JpaRepository<AsyncTaskEntity, String> {

    /** tick 扫描主路径（索引 idx_async_tasks_due）：到期任务按时刻升序 */
    List<AsyncTaskEntity> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            String status, Instant now, Pageable pageable);

    Optional<AsyncTaskEntity> findByDedupKey(String dedupKey);

    /**
     * FR-06 乐观认领：置 next_run_at=NULL（在跑期间恒空，tick 不重扫），
     * 带 status/next_run_at 条件堵「扫描后被 PAUSED」与并发认领；0 行=已被认领或已暂停。
     * WHERE 不依赖时间戳等值比较（规避 PG micros 与 Java nanos 精度差）。
     */
    @Modifying
    @Transactional
    @Query("update AsyncTaskEntity t set t.nextRunAt = null, t.updatedAt = :now "
            + "where t.id = :id and t.status = 'ACTIVE' and t.nextRunAt is not null")
    int claim(@Param("id") String id, @Param("now") Instant now);

    /**
     * 管理端组合查询：type/status/actor 精确匹配（空=不限，actor 由非 admin 强制传入），
     * keyword 对 name/taskType 大小写不敏感包含。可空筛选参数的 null 判断一律 cast
     * （孤立 `? is null` 无列可推断，PG 报 42P18）；keyword 另因只在 concat 内会落 bytea。
     */
    @Query("""
            select t from AsyncTaskEntity t
            where (cast(:type as string) is null or t.taskType = :type)
              and (cast(:status as string) is null or t.status = :status)
              and (cast(:actor as string) is null or t.actor = :actor)
              and (cast(:keyword as string) is null
                   or lower(t.name) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(t.taskType) like lower(concat('%', cast(:keyword as string), '%')))
            order by t.createdAt desc, t.id desc
            """)
    Page<AsyncTaskEntity> search(@Param("type") String type, @Param("status") String status,
                                 @Param("actor") String actor, @Param("keyword") String keyword,
                                 Pageable pageable);
}
