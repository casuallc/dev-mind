package com.devmind.knowledge.repo;
import com.devmind.knowledge.model.KnowledgeSourceBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface KnowledgeSourceBindingRepository extends JpaRepository<KnowledgeSourceBindingEntity,Long> {
    interface Current {
        String getMode();int getMaxAgeSeconds();String getApprovedBy();boolean isRevoked();long getRevision();
    }
    @org.springframework.data.jpa.repository.Query("select b.mode as mode,b.maxAgeSeconds as maxAgeSeconds,b.approvedBy as approvedBy,b.revoked as revoked,b.revision as revision from KnowledgeSourceBindingEntity b where b.entryId=:id")
    java.util.Optional<Current> findCurrentById(@org.springframework.data.repository.query.Param("id") Long id);
}
