package com.devmind.build.repo;

import com.devmind.build.model.BuildEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BuildRepository extends JpaRepository<BuildEntity, Long> {

    List<BuildEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<BuildEntity> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

    /** 并发限制：统计该项目的活动构建数 */
    long countByProjectIdAndStatusIn(String projectId, Collection<String> statuses);
}
