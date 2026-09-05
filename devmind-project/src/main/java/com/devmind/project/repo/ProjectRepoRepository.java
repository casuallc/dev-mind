package com.devmind.project.repo;

import com.devmind.project.model.ProjectRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepoRepository extends JpaRepository<ProjectRepoEntity, Long> {

    List<ProjectRepoEntity> findByProjectIdOrderBySortOrderAscIdAsc(String projectId);

    Optional<ProjectRepoEntity> findByProjectIdAndIsPrimaryTrue(String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndPath(String projectId, String path);

    /** CAP-29：全局仓库被多少项目行引用（删除保护 + 状态镜像扇出） */
    long countByGitRepoId(Long gitRepoId);

    List<ProjectRepoEntity> findByGitRepoId(Long gitRepoId);

    void deleteByProjectId(String projectId);
}
