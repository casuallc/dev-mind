package com.devmind.artifact.repo;

import com.devmind.artifact.model.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, Long> {

    List<ArtifactEntity> findByProjectIdOrderByIdDesc(String projectId);

    List<ArtifactEntity> findByProjectIdAndWorkItemIdOrderByIdDesc(String projectId, String workItemId);

    List<ArtifactEntity> findByProjectIdAndRequirementIdOrderByIdDesc(String projectId, String requirementId);

    /** 按生产者反查（如某次构建登记的产物） */
    List<ArtifactEntity> findByProducerTypeAndProducerId(String producerType, Long producerId);

    /** 信息类产物幂等清理：同一 (type, path 引用) 只留最新 */
    List<ArtifactEntity> findByProjectIdAndTypeAndPath(String projectId, String type, String path);
}
