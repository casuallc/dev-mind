package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, Long> {

    List<KnowledgeEntryEntity> findByScopeOrderByUpdatedAtDesc(String scope);

    List<KnowledgeEntryEntity> findByScopeAndProjectIdOrderByUpdatedAtDesc(String scope, String projectId);

    List<KnowledgeEntryEntity> findByStatusOrderByUpdatedAtDesc(String status);

    Optional<KnowledgeEntryEntity> findByIdAndStatus(Long id, String status);

    @Query("select e from KnowledgeEntryEntity e where e.status = 'active' and (e.scope = 'global' or e.projectId = :projectId) " +
            "and (lower(e.name) like lower(concat('%', :q, '%')) or lower(cast(e.contentMd as string)) like lower(concat('%', :q, '%')) " +
            "or lower(e.tags) like lower(concat('%', :q, '%'))) order by e.updatedAt desc")
    List<KnowledgeEntryEntity> searchActive(@Param("q") String q, @Param("projectId") String projectId);
}
