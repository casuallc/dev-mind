package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, Long> {

    List<KnowledgeEntryEntity> findByScopeOrderByCreatedAtDesc(String scope);

    List<KnowledgeEntryEntity> findByScopeAndProjectIdOrderByCreatedAtDesc(String scope, String projectId);

    List<KnowledgeEntryEntity> findByStatusOrderByCreatedAtDesc(String status);

    Optional<KnowledgeEntryEntity> findByIdAndStatus(Long id, String status);

    List<KnowledgeEntryEntity> findByKbIdOrderByCreatedAtDesc(Long kbId);

    List<KnowledgeEntryEntity> findByKbIdAndStatusOrderByCreatedAtDesc(Long kbId, String status);

    List<KnowledgeEntryEntity> findByKbIdInOrderByCreatedAtDesc(List<Long> kbIds);

    List<KnowledgeEntryEntity> findByKbIdInAndStatusOrderByCreatedAtDesc(List<Long> kbIds, String status);

    List<KnowledgeEntryEntity> findByIndexStatus(String indexStatus);

    List<KnowledgeEntryEntity> findByIndexStatusIn(List<String> indexStatuses);

    long countByKbId(Long kbId);

    @Query("select e from KnowledgeEntryEntity e where e.status = 'active' and (e.scope = 'global' or e.projectId = :projectId) " +
            "and (lower(e.name) like lower(concat('%', cast(:q as string), '%')) or lower(cast(e.contentMd as string)) like lower(concat('%', cast(:q as string), '%')) " +
            "or lower(e.tags) like lower(concat('%', cast(:q as string), '%'))) order by e.createdAt desc")
    List<KnowledgeEntryEntity> searchActive(@Param("q") String q, @Param("projectId") String projectId);

    /** CAP-44 库模型检索：指定库集合内 LIKE 命中（embedding 未配置时的降级检索）。 */
    @Query("select e from KnowledgeEntryEntity e where e.kbId in :kbIds and e.status = 'active' " +
            "and (lower(e.name) like lower(concat('%', cast(:q as string), '%')) or lower(cast(e.contentMd as string)) like lower(concat('%', cast(:q as string), '%')) " +
            "or lower(e.tags) like lower(concat('%', cast(:q as string), '%'))) order by e.createdAt desc")
    List<KnowledgeEntryEntity> searchInBases(@Param("kbIds") List<Long> kbIds, @Param("q") String q);
}
