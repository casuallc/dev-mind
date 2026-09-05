package com.devmind.worklog.repo;

import com.devmind.worklog.model.GitRepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitRepositoryRepository extends JpaRepository<GitRepositoryEntity, Long> {

    Optional<GitRepositoryEntity> findByLocalPath(String localPath);
}
