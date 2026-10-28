package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

    List<KnowledgeChunkEntity> findByEntryIdOrderByChunkIndexAsc(Long entryId);
    List<KnowledgeChunkEntity> findByReleaseIdOrderByReleaseChunkIndexAsc(Long releaseId);
    interface ScopeRef {Long getId();Long getReleaseId();String getApplicabilityJson();}
    @Query("select c.id as id,c.releaseId as releaseId,c.applicabilityJson as applicabilityJson from KnowledgeChunkEntity c where c.releaseId in :ids")
    List<ScopeRef> findScopeRefs(@Param("ids") java.util.Collection<Long> releaseIds);
    @Query("select coalesce(max(c.chunkIndex), -1) from KnowledgeChunkEntity c where c.entryId = :entryId")
    int maxChunkOrdinal(@Param("entryId") Long entryId);
    @Modifying
    @Query("delete from KnowledgeChunkEntity c where c.entryId = :entryId and c.releaseId is null")
    void deleteUnpublishedByEntryId(@Param("entryId") Long entryId);

    List<KnowledgeChunkEntity> findByKbId(Long kbId);

    List<KnowledgeChunkEntity> findByKbIdIn(List<Long> kbIds);

    @Modifying
    @Query("delete from KnowledgeChunkEntity c where c.entryId = :entryId")
    void deleteByEntryId(@Param("entryId") Long entryId);

    long countByKbId(Long kbId);
    long countByEntryIdIn(java.util.Collection<Long> entryIds);
}
