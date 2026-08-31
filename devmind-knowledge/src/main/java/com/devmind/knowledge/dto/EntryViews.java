package com.devmind.knowledge.dto;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import com.devmind.knowledge.model.KnowledgeProposalEntity;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 实体 → 视图 的静态映射工具。 */
public final class EntryViews {

    private EntryViews() {
    }

    public static EntryView entry(KnowledgeEntryEntity e) {
        return new EntryView(
                e.getId(), e.getScope(), e.getProjectId(), e.getName(), e.getPath(),
                e.getContentMd(), splitTags(e.getTags()), e.getSourceProject(),
                e.getHitCount(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt());
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
