package com.devmind.project.repo;

import com.devmind.project.model.EnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, Long> {

    /** 列表 API：创建时间倒排 */
    List<EnvironmentEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 内部取默认环境用：保持最早创建在前 */
    List<EnvironmentEntity> findByProjectIdOrderByIdAsc(String projectId);

    Optional<EnvironmentEntity> findByProjectIdAndName(String projectId, String name);

    void deleteByProjectId(String projectId);
}
