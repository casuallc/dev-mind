package com.devmind.test.repo;

import com.devmind.test.model.TestSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuiteEntity, Long> {

    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 事件触发批量跑用：保持创建顺序正序执行 */
    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtAsc(String projectId);

    /** CAP-69：按 kind 列独立套件（script 套件 projectId 为 NULL，不能按项目查） */
    List<TestSuiteEntity> findByKindOrderByCreatedAtDesc(String kind);

    void deleteByProjectId(String projectId);
}
