package com.devmind.task.repo;

import com.devmind.task.model.AsyncTaskRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AsyncTaskRunRepository extends JpaRepository<AsyncTaskRunEntity, String> {

    /** 重叠判定：存在未终态 run（QUEUED 含未来时刻的重试排队） */
    boolean existsByTaskIdAndStatusIn(String taskId, Collection<String> statuses);

    /** run 级调度扫描：到期 QUEUED run（首轮=认领时刻即到期；重试=退避到期） */
    List<AsyncTaskRunEntity> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            String status, Instant now, Pageable pageable);

    /** 僵尸收割 / 启动扫描 */
    List<AsyncTaskRunEntity> findByStatus(String status);

    Page<AsyncTaskRunEntity> findByTaskIdOrderByRunSeqDescAttemptDesc(String taskId, Pageable pageable);

    /** 执行记录横查（管理页「执行记录」视图）：按状态过滤，scheduled_at 倒序 */
    @Query("select r from AsyncTaskRunEntity r where (:status is null or r.status = :status) "
            + "order by r.scheduledAt desc")
    Page<AsyncTaskRunEntity> queryRuns(@Param("status") String status, Pageable pageable);

    /** 认领 run：QUEUED→RUNNING（0 行=已被并发认领，调用方放弃） */
    @Modifying
    @Transactional
    @Query("update AsyncTaskRunEntity r set r.status = 'RUNNING', r.startedAt = :now "
            + "where r.id = :id and r.status = 'QUEUED'")
    int start(@Param("id") String id, @Param("now") Instant now);

    /**
     * 终态流转（FR-03 乐观条件）：只在 QUEUED/RUNNING 内翻转，
     * 收割器与迟到 handler 双写时后到者丢弃（0 行）。
     */
    @Modifying
    @Transactional
    @Query("update AsyncTaskRunEntity r set r.status = :status, r.finishedAt = :finishedAt, "
            + "r.durationMs = :durationMs, r.error = :error, r.logs = :logs "
            + "where r.id = :id and r.status in ('QUEUED', 'RUNNING')")
    int finish(@Param("id") String id, @Param("status") String status,
               @Param("finishedAt") Instant finishedAt, @Param("durationMs") Long durationMs,
               @Param("error") String error, @Param("logs") String logs);

    /** 任务 cancel 级联：未派发的 QUEUED run 直接判 CANCELLED（RUNNING 由 Executor.interrupt 处理） */
    @Modifying
    @Transactional
    @Query("update AsyncTaskRunEntity r set r.status = 'CANCELLED', r.finishedAt = :now "
            + "where r.taskId = :taskId and r.status = 'QUEUED'")
    int cancelQueuedByTask(@Param("taskId") String taskId, @Param("now") Instant now);
}
