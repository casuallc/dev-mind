package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeEntryVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeEntryVersionRepository extends JpaRepository<KnowledgeEntryVersionEntity, Long> {

    List<KnowledgeEntryVersionEntity> findByEntryIdOrderByContentVersionDesc(Long entryId);

    Optional<KnowledgeEntryVersionEntity> findByEntryIdAndContentVersion(Long entryId, int contentVersion);

    void deleteByEntryId(Long entryId);
}
