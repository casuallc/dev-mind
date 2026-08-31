package com.devmind.docs.store;

import com.devmind.docs.model.DocumentEntity;

/**
 * docs-repo 文件路径映射（CAP-03 §5）：
 * tasks/&lt;taskId&gt;/…、designs/&lt;taskId&gt;/&lt;project&gt;/…、api-suite/&lt;project&gt;/…、reports/…
 */
public final class DocPaths {

    private DocPaths() {
    }

    /** 相对 docs-repo 根的文件路径（始终使用 '/' 分隔，入库展示用）。 */
    public static String filePath(DocumentEntity e) {
        String title = slug(e.getTitle() == null ? "untitled" : e.getTitle());
        String task = blank(e.getTaskId()) ? "_" : slug(e.getTaskId());
        String proj = blank(e.getProjectId()) ? "general" : slug(e.getProjectId());
        return switch (e.getKind() == null ? "" : e.getKind()) {
            case "requirement" -> "tasks/" + task + "/" + title + ".md";
            case "design" -> "designs/" + task + "/" + proj + "/" + title + ".md";
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
