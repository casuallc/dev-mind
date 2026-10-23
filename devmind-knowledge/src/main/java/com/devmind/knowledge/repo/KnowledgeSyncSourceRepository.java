package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeSyncSourceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSyncSourceRepository extends JpaRepository<KnowledgeSyncSourceEntity, Long> {

    List<KnowledgeSyncSourceEntity> findByKbIdOrderByCreatedAtDesc(Long kbId);

    List<KnowledgeSyncSourceEntity> findByOrderByCreatedAtDesc();

    List<KnowledgeSyncSourceEntity> findByKbIdAndEnabledTrue(Long kbId);
}
