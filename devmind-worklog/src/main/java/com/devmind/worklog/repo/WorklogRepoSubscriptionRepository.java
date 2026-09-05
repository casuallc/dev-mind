package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogRepoSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorklogRepoSubscriptionRepository extends JpaRepository<WorklogRepoSubscriptionEntity, Long> {

    List<WorklogRepoSubscriptionEntity> findByUserId(String userId);

    Optional<WorklogRepoSubscriptionEntity> findByUserIdAndRepoId(String userId, Long repoId);

    void deleteByUserIdAndRepoId(String userId, Long repoId);

    void deleteByRepoId(Long repoId);

    /** 定时调度覆盖范围：所有有订阅的用户 */
    @Query("select distinct s.userId from WorklogRepoSubscriptionEntity s")
    List<String> findDistinctUserIds();
}
