package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgePolicyRepository extends JpaRepository<KnowledgePolicyEntity, Long> {
    interface PolicyRef {
        Long getId();
        String getOwnerId();
        String getVisibility();
        long getRevision();
    }
    /** Scalar projection bypasses stale managed entities in an OSIV/persistence context. */
    @org.springframework.data.jpa.repository.Query("select p.id as id, p.ownerId as ownerId, p.visibility as visibility, p.revision as revision from KnowledgePolicyEntity p where p.id in :ids")
    java.util.List<PolicyRef> findAccessSnapshots(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
