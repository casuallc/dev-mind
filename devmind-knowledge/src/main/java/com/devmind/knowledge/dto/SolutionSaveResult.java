package com.devmind.knowledge.dto;

/** CAP-84 FR-24：方案沉淀结果（新文档 id/版本）。 */
public record SolutionSaveResult(Long documentId, int versionNo, String title, String kind) {
}
