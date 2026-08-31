package com.devmind.docs.store;

import com.devmind.docs.model.DocumentEntity;

/**
 * docs-repo 文件路径映射（CAP-03 §5 / CAP-13）：
 * requirements/&lt;requirementId&gt;/…、designs/&lt;requirementId&gt;/&lt;project&gt;/…、api-suite/&lt;project&gt;/…、reports/…
 */
public final class DocPaths {

    private DocPaths() {
    }

    /** 相对 docs-repo 根的文件路径（始终使用 '/' 分隔，入库展示用）。 */
    public static String filePath(DocumentEntity e) {
        String title = slug(e.getTitle() == null ? "untitled" : e.getTitle());
        String req = blank(e.getRequirementId()) ? "_" : slug(e.getRequirementId());
        String proj = blank(e.getProjectId()) ? "general" : slug(e.getProjectId());
        return switch (e.getKind() == null ? "" : e.getKind()) {
            case "requirement" -> "requirements/" + req + "/" + title + ".md";
            case "design" -> "designs/" + req + "/" + proj + "/" + title + ".md";
            case "api-suite" -> "api-suite/" + proj + "/" + title + ".md";
            case "report" -> "reports/" + title + ".md";
            default -> "docs/" + title + ".md";
        };
    }

    /** 仅文件名安全 slug（防路径穿越/非法字符）。 */
    public static String slug(String s) {
        String cleaned = s.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceFirst("^-", "")
                .replaceFirst("-$", "");
        return cleaned.isBlank() ? "untitled" : cleaned;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
