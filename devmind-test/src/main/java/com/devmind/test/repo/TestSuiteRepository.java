package com.devmind.test.repo;

import com.devmind.test.model.TestSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuiteEntity, Long> {

    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 事件触发批量跑用：保持创建顺序正序执行 */
    List<TestSuiteEntity> findByProjectIdOrderByCreatedAtAsc(String projectId);

    /** CAP-69：按 kind 列套件（script 套件全量列表用，含未绑项目的） */
    List<TestSuiteEntity> findByKindOrderByCreatedAtDesc(String kind);

    /** CAP-69：项目绑定的脚本套件（项目「测试」页套件列表用） */
    List<TestSuiteEntity> findByProjectIdAndKindOrderByCreatedAtDesc(String projectId, String kind);

    void deleteByProjectId(String projectId);
}
