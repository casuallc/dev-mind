package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeReleaseEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface KnowledgeReleaseRepository extends JpaRepository<KnowledgeReleaseEntity,Long> {
    interface SelectionRef {
        Long getId();String getApplicabilityJson();String getSupersededScopesJson();String getSectionScopesJson();
        java.time.Instant getEffectiveFrom();java.time.Instant getEffectiveTo();
    }
    @org.springframework.data.jpa.repository.Query("select r.id as id,r.applicabilityJson as applicabilityJson,r.supersededScopesJson as supersededScopesJson,r.sectionScopesJson as sectionScopesJson,r.effectiveFrom as effectiveFrom,r.effectiveTo as effectiveTo from KnowledgeReleaseEntity r where r.entryId in :ids and r.state='PUBLISHED'")
    List<SelectionRef> findCurrentSelection(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
    List<KnowledgeReleaseEntity> findByEntryIdOrderByPublishedAtDesc(Long entryId);
    List<KnowledgeReleaseEntity> findByEntryIdInAndState(Collection<Long> entryIds,String state);
}
