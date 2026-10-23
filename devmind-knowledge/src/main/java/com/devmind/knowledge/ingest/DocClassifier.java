package com.devmind.knowledge.ingest;

import com.devmind.knowledge.model.KnowledgeEntryEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CAP-84 FR-04 目录分类：相对路径关键词 → doc_type，路径目录与文件名 → 标签。
 * 规则优先于批次默认值；无法判断返回 null 由调用方回退默认类型。
 */
public final class DocClassifier {

    private static final int MAX_TAGS = 12;
    private static final int MAX_TAGS_CHARS = 480;

    private DocClassifier() {
    }

    public static String classify(String relativePath) {
        String p = normalize(relativePath);
        if (containsAny(p, "常见问题", "faq", "问答", "troubleshoot", "troubleshooting", "故障")) {
            return KnowledgeEntryEntity.DOC_FAQ;
        }
        if (containsAny(p, "维护", "运维", "部署", "oncall", "maint", "ops", "operation")) {
            return KnowledgeEntryEntity.DOC_MAINT;
        }
        if (containsAny(p, "产物", "交付", "报告", "release", "artifact", "deliver", "output")) {
            return KnowledgeEntryEntity.DOC_ARTIFACT;
        }
        if (containsAny(p, "技术", "研发", "开发", "architecture", "tech", "dev", "design", "设计")) {
            return KnowledgeEntryEntity.DOC_TECH;
        }
        return null;
    }

    /** 目录名 + 文件名主干做标签（去重、忽略大小写、限长）。 */
    public static List<String> tags(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        Set<String> tags = new LinkedHashSet<>();
        for (String segment : normalized.split("/")) {
            String s = segment.strip();
            if (s.isEmpty()) {
                continue;
            }
            int dot = s.lastIndexOf('.');
            if (dot > 0) {
                s = s.substring(0, dot);
            }
            String tag = s.replaceAll("[\\s_]+", "-").strip();
            if (tag.length() < 2 || tag.length() > 40) {
                continue;
            }
            if (tags.stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                tags.add(tag);
            }
            if (tags.size() >= MAX_TAGS) {
                break;
            }
        }
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String tag : tags) {
            if (total + tag.length() + 1 > MAX_TAGS_CHARS) {
                break;
            }
            out.add(tag);
            total += tag.length() + 1;
        }
        return out;
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static boolean containsAny(String path, String... keywords) {
        for (String keyword : keywords) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String relativePath) {
        return normalizePath(relativePath).toLowerCase(Locale.ROOT);
    }
}
