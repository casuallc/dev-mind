package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

    List<KnowledgeChunkEntity> findByEntryIdOrderByChunkIndexAsc(Long entryId);

    List<KnowledgeChunkEntity> findByKbId(Long kbId);

    List<KnowledgeChunkEntity> findByKbIdIn(List<Long> kbIds);

    @Modifying
    @Query("delete from KnowledgeChunkEntity c where c.entryId = :entryId")
    void deleteByEntryId(@Param("entryId") Long entryId);

    long countByKbId(Long kbId);
}
