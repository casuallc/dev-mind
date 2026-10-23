package com.devmind.knowledge.repo;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, Long> {

    List<KnowledgeEntryEntity> findByScopeOrderByCreatedAtDesc(String scope);

    List<KnowledgeEntryEntity> findByScopeAndProjectIdOrderByCreatedAtDesc(String scope, String projectId);

    List<KnowledgeEntryEntity> findByStatusOrderByCreatedAtDesc(String status);

    Optional<KnowledgeEntryEntity> findByIdAndStatus(Long id, String status);

    /** CAP-45 飞书导入判重：同库同外部主键 */
    Optional<KnowledgeEntryEntity> findByKbIdAndExternalId(Long kbId, String externalId);

    /** CAP-84 本地上传判重：同库同来源路径 */
    Optional<KnowledgeEntryEntity> findByKbIdAndSourceUri(Long kbId, String sourceUri);

    /** CAP-84 重建索引：按 id 键集分页，避免一次加载万级条目正文 */
    List<KnowledgeEntryEntity> findTop100ByIdGreaterThanOrderByIdAsc(Long id);

    List<KnowledgeEntryEntity> findTop100ByKbIdAndIdGreaterThanOrderByIdAsc(Long kbId, Long id);

    List<KnowledgeEntryEntity> findByKbIdOrderByCreatedAtDesc(Long kbId);

    List<KnowledgeEntryEntity> findByKbIdAndStatusOrderByCreatedAtDesc(Long kbId, String status);

    List<KnowledgeEntryEntity> findByKbIdInOrderByCreatedAtDesc(List<Long> kbIds);

    List<KnowledgeEntryEntity> findByKbIdInAndStatusOrderByCreatedAtDesc(List<Long> kbIds, String status);

    List<KnowledgeEntryEntity> findByIndexStatus(String indexStatus);

    List<KnowledgeEntryEntity> findByIndexStatusIn(List<String> indexStatuses);

    /** CAP-85 FR-27：同步源归属条目（删除检测用，只取 id/externalId，不加载正文） */
    @Query("select e.id as id, e.externalId as externalId from KnowledgeEntryEntity e "
            + "where e.syncSourceId = :sourceId")
    List<SyncEntryRef> findRefsBySyncSourceId(@Param("sourceId") Long sourceId);

    interface SyncEntryRef {
        Long getId();

        String getExternalId();
    }

    /** CAP-85 FR-28：待自动增强条目（只取 id，正文逐条加载，避免整批 LOB 进内存） */
    @Query("select e.id from KnowledgeEntryEntity e where e.status = 'active' "
            + "and (e.autoEnrich is null or e.autoEnrich = true) "
            + "and (e.enrichedAt is null or e.updatedAt > e.enrichedAt) order by e.id")
    List<Long> findEnrichCandidateIds(Pageable page);

    @Query("select e.id from KnowledgeEntryEntity e where e.status = 'active' and e.kbId = :kbId "
            + "and (e.autoEnrich is null or e.autoEnrich = true) "
            + "and (e.enrichedAt is null or e.updatedAt > e.enrichedAt) order by e.id")
    List<Long> findEnrichCandidateIdsByKbId(@Param("kbId") Long kbId, Pageable page);

    /** CAP-85 FR-31：治理页增强状态清单（轻量投影，按最近增强/更新时间倒序） */
    @Query("select e.id as id, e.kbId as kbId, e.name as name, e.docType as docType, "
            + "e.autoEnrich as autoEnrich, e.enrichedAt as enrichedAt, e.enrichError as enrichError, "
            + "e.updatedAt as updatedAt from KnowledgeEntryEntity e where e.status = 'active' "
            + "order by coalesce(e.enrichedAt, e.updatedAt) desc")
    List<EnrichEntryRef> findEnrichEntries(Pageable page);

    @Query("select e.id as id, e.kbId as kbId, e.name as name, e.docType as docType, "
            + "e.autoEnrich as autoEnrich, e.enrichedAt as enrichedAt, e.enrichError as enrichError, "
            + "e.updatedAt as updatedAt from KnowledgeEntryEntity e where e.status = 'active' "
            + "and e.kbId = :kbId order by coalesce(e.enrichedAt, e.updatedAt) desc")
    List<EnrichEntryRef> findEnrichEntriesByKbId(@Param("kbId") Long kbId, Pageable page);

    interface EnrichEntryRef {
        Long getId();

        Long getKbId();

        String getName();

        String getDocType();

        Boolean getAutoEnrich();

        Instant getEnrichedAt();

        String getEnrichError();

        Instant getUpdatedAt();
    }

    /** CAP-85 FR-29：生命周期清单（轻量投影，不加载正文 LOB） */
    @Query("select e.id as id, e.kbId as kbId, e.name as name, e.docType as docType, e.owner as owner, "
            + "e.validTo as validTo, e.lastReviewedAt as lastReviewedAt, e.updatedAt as updatedAt, "
            + "e.status as status from KnowledgeEntryEntity e where e.status = 'active'")
    List<LifecycleRef> findLifecycleRefs();

    interface LifecycleRef {
        Long getId();

        Long getKbId();

        String getName();

        String getDocType();

        String getOwner();

        Instant getValidTo();

        Instant getLastReviewedAt();

        Instant getUpdatedAt();

        String getStatus();
    }

    /** CAP-85 FR-29：重复检测清单（只取指纹与展示字段） */
    @Query("select e.id as id, e.kbId as kbId, e.name as name, e.docType as docType, "
            + "e.updatedAt as updatedAt, e.simHash as simHash "
            + "from KnowledgeEntryEntity e where e.status = 'active'")
    List<DuplicateRef> findDuplicateRefs();

    interface DuplicateRef {
        Long getId();

        Long getKbId();

        String getName();

        String getDocType();

        Instant getUpdatedAt();

        String getSimHash();
    }

    @Query("select count(e) from KnowledgeEntryEntity e where e.simHash is null or e.simHash = ''")
    long countMissingSimHash();

    @Query("select e.id from KnowledgeEntryEntity e where e.simHash is null or e.simHash = '' order by e.id")
    List<Long> findMissingSimHashIds(Pageable page);

    long countByKbId(Long kbId);

    /** CAP-48 FR-06 索引健康度：库内条目按 index_status 分组计数（返回 [status, count]） */
    @Query("select e.indexStatus, count(e) from KnowledgeEntryEntity e where e.kbId = :kbId group by e.indexStatus")
    List<Object[]> countByIndexStatus(@Param("kbId") Long kbId);

    /**
     * CAP-48 FR-06 失配条目数：已 ready 但血缘和本库当前端点对不上（换过端点/模型/维度）。
     * indexed_* 为 null 视为失配（CAP-48 之前建的索引没有血缘）。
     */
    @Query("select count(e) from KnowledgeEntryEntity e where e.kbId = :kbId and e.indexStatus = 'ready' "
            + "and (e.indexedEndpointId is null or e.indexedEndpointId <> :endpointId "
            + "or e.indexedDimensions is null or e.indexedDimensions <> :dimensions)")
    long countMismatched(@Param("kbId") Long kbId, @Param("endpointId") Long endpointId,
                         @Param("dimensions") int dimensions);

    /** 失配条目（重建索引用，谓词同 {@link #countMismatched}） */
    @Query("select e from KnowledgeEntryEntity e where e.kbId = :kbId and e.indexStatus = 'ready' "
            + "and (e.indexedEndpointId is null or e.indexedEndpointId <> :endpointId "
            + "or e.indexedDimensions is null or e.indexedDimensions <> :dimensions)")
    List<KnowledgeEntryEntity> findMismatched(@Param("kbId") Long kbId, @Param("endpointId") Long endpointId,
                                              @Param("dimensions") int dimensions);

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
