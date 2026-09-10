package com.devmind.test.repo;

import com.devmind.test.model.TestSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuiteEntity, Long> {

    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 事件触发批量跑用：保持创建顺序正序执行 */
    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtAsc(String projectId);

    void deleteByProjectId(String projectId);
}
