package com.devmind.project.repo;

import com.devmind.project.model.RelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationRepository extends JpaRepository<RelationEntity, String> {

    List<RelationEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 某端点涉及的边（双向查：作为 from 或 to） */
    List<RelationEntity> findByFromTypeAndFromIdOrToTypeAndToId(
            String fromType, String fromId, String toType, String toId);

    void deleteByFromTypeAndFromId(String fromType, String fromId);

    void deleteByToTypeAndToId(String toType, String toId);

    void deleteByProjectId(String projectId);
}
