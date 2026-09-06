package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorklogEntryRepository extends JpaRepository<WorklogEntryEntity, Long> {

    List<WorklogEntryEntity> findByUserIdAndWorkDateBetweenOrderByWorkDateAscIdAsc(
            String userId, LocalDate from, LocalDate to);

    /** 分页列表（排序由 Pageable 传入：workDate desc, id desc 最新在前） */
    Page<WorklogEntryEntity> findByUserIdAndWorkDateBetween(
            String userId, LocalDate from, LocalDate to, Pageable pageable);

    List<WorklogEntryEntity> findByUserIdAndWorkDateOrderByIdAsc(String userId, LocalDate workDate);

    Optional<WorklogEntryEntity> findByIdAndUserId(Long id, String userId);

    /** git 导入幂等判定 */
    boolean existsByUserIdAndRepoIdAndCommitSha(String userId, Long repoId, String commitSha);
}
