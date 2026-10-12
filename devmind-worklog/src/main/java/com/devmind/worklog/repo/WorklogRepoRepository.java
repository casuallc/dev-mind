package com.devmind.worklog.repo;

import com.devmind.worklog.model.WorklogRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorklogRepoRepository extends JpaRepository<WorklogRepoEntity, Long> {

    Optional<WorklogRepoEntity> findByRepoKey(String repoKey);
}
