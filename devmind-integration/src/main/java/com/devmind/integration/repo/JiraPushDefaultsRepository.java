package com.devmind.integration.repo;

import com.devmind.integration.model.JiraPushDefaultsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * CAP-47 FR-10：项目级 Jira 推送默认值（一项目一行）。
 */
public interface JiraPushDefaultsRepository extends JpaRepository<JiraPushDefaultsEntity, Long> {

    Optional<JiraPushDefaultsEntity> findByProjectId(String projectId);
}
