package com.devmind.session.repo;

import com.devmind.session.model.WorkspaceCleanupConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** CAP-114：工作区定时清理策略（单行配置）。 */
public interface WorkspaceCleanupConfigRepository extends JpaRepository<WorkspaceCleanupConfigEntity, Long> {
}
