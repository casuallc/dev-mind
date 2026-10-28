package com.devmind.knowledge.dto;

import com.devmind.knowledge.model.KnowledgeBaseEntity;
import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 实体 → 视图 的静态映射工具。 */
public final class EntryViews {

    private EntryViews() {
    }

    /** 条目视图（CAP-94：不再派生 scope/projectId，归属只看 kbId）。 */
    public static EntryView entry(KnowledgeEntryEntity e, KnowledgeBaseEntity kb) {
        return new EntryView(
                e.getId(), e.getKbId(), e.getName(), e.getPath(),
                e.getContentMd(), splitTags(e.getTags()), e.getSourceProject(),
                e.getHitCount(), e.getStatus(), e.getSource(), e.getExternalId(),
                e.getIndexStatus(), e.getIndexError(),
                e.getDocType(), e.getSourceUri(), e.getMimeType(), e.getSummary(), e.getOwner(),
                e.getContentVersion(), e.getValidFrom(), e.getValidTo(), e.getLastReviewedAt(),
                !Boolean.FALSE.equals(e.getAutoEnrich()), e.getEnrichedAt(), e.getEnrichError(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getReleaseSetRevision(),e.getParseReportJson());
    }

    public static KnowledgeBaseView base(KnowledgeBaseEntity kb,
                                         long entryCount, long chunkCount,
                                         String modelEndpointName, KnowledgeBaseView.IndexStats indexStats,
                                         List<com.devmind.common.knowledge.KnowledgeAccessPolicy.Action> effectiveActions) {
        return new KnowledgeBaseView(
                kb.getId(), kb.getName(), kb.getDescription(),
                kb.getModelEndpointId(), modelEndpointName, kb.getStatus(),
                entryCount, chunkCount, indexStats, kb.getCreatedAt(), kb.getUpdatedAt(), effectiveActions);
    }

    /** 提案视图；kbNames 用于解析采纳目标库名（可为 null → adoptedKbName=null）。 */
    public static ProposalView proposal(KnowledgeProposalEntity p,
                                        java.util.Map<Long, String> kbNames) {
        String adoptedKbName = p.getAdoptedKbId() == null || kbNames == null
                ? null : kbNames.get(p.getAdoptedKbId());
        return new ProposalView(
                p.getId(), p.getTitle(), p.getContentMd(),
                p.getSourceSessionId(), p.getSourceProject(), p.getStatus(),
                p.getAdoptedKbId(), adoptedKbName, p.getCreatedAt(), p.getAdoptedAt(),
                TriageViews.of(p.getTriageJson(), p.getTriageAt(), p.isTriageDegraded()));
    }

    public static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    public static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(",", tags);
    }
}
