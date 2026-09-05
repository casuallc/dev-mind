package com.devmind.project.repo;

import com.devmind.project.model.GitRepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** CAP-29 全局代码仓库登记。 */
public interface GitRepositoryRepository extends JpaRepository<GitRepositoryEntity, Long> {

    Optional<GitRepositoryEntity> findByLocalPath(String localPath);

    Optional<GitRepositoryEntity> findByRemoteUrlKey(String remoteUrlKey);

    /** 定时 fetch 覆盖范围：服务端克隆 + 克隆就绪 + 启用 */
    List<GitRepositoryEntity> findBySourceTypeAndCloneStatusAndStatus(
            String sourceType, String cloneStatus, String status);
}
