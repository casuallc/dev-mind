package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogRepoMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorklogRepoMemberRepository extends JpaRepository<WorklogRepoMemberEntity, Long> {

    Optional<WorklogRepoMemberEntity> findByRepoIdAndUserId(Long repoId, String userId);

    List<WorklogRepoMemberEntity> findByRepoId(Long repoId);

    List<WorklogRepoMemberEntity> findByUserId(String userId);
}
