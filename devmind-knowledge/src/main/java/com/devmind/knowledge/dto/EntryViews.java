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

    /**
     * 条目视图：scope/projectId 由 KB 派生（CAP-44）；kb 为 null（迁移未跑的存量行）
     * 时兜底用历史列。
     */
    public static EntryView entry(KnowledgeEntryEntity e, KnowledgeBaseEntity kb) {
        String scope = kb != null ? kb.getScope() : e.getScope();
        String projectId = kb != null ? kb.getProjectId() : e.getProjectId();
        return new EntryView(
                e.getId(), e.getKbId(), scope, projectId, e.getName(), e.getPath(),
                e.getContentMd(), splitTags(e.getTags()), e.getSourceProject(),
                e.getHitCount(), e.getStatus(), e.getSource(), e.getExternalId(),
                e.getIndexStatus(), e.getIndexError(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    public static KnowledgeBaseView base(KnowledgeBaseEntity kb, String projectName,
                                         long entryCount, long chunkCount) {
        return new KnowledgeBaseView(
                kb.getId(), kb.getName(), kb.getDescription(), kb.getScope(), kb.getProjectId(),
                projectName, kb.getInjectMode(), kb.getEmbeddingModel(), kb.getStatus(),
                entryCount, chunkCount, kb.getCreatedAt(), kb.getUpdatedAt());
    }

    public static ProposalView proposal(KnowledgeProposalEntity p) {
        return new ProposalView(
                p.getId(), p.getTitle(), p.getContentMd(), p.getTargetScope(),
                p.getTargetProjectId(), p.getSourceSessionId(), p.getStatus(),
                p.getAdoptedTo(), p.getAdoptedProjectId(), p.getCreatedAt(), p.getAdoptedAt());
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
