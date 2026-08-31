package com.devmind.docs.dto;

import com.devmind.docs.model.DocumentEntity;
import com.devmind.docs.model.DocumentVersionEntity;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 实体 → 视图 静态映射。 */
public final class DocViews {

    private DocViews() {
    }

    public static DocView doc(DocumentEntity e, String filePath) {
        return new DocView(
                e.getId(), e.getKind(), e.getRequirementId(), e.getWorkItemId(), e.getProjectId(), e.getTitle(),
                e.getCurrentVersion(), e.getStatus(), splitTags(e.getTags()),
                filePath, e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static DocDetail detail(DocumentEntity e, DocumentVersionEntity v, String filePath) {
        return new DocDetail(
                e.getId(), e.getKind(), e.getRequirementId(), e.getWorkItemId(), e.getProjectId(), e.getTitle(),
                v.getVersionNo(), e.getStatus(), splitTags(e.getTags()),
                v.getContentMd(), v.getChangeNote(), v.getCommitSha(), filePath,
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static DocVersionView version(DocumentVersionEntity v) {
        return new DocVersionView(
                v.getDocumentId(), v.getVersionNo(), v.getChangeNote(),
                v.getCommitSha(), v.getCreatedBy(), v.getCreatedAt());
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
