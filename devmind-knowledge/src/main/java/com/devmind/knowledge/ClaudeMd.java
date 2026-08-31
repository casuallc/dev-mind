package com.devmind.knowledge;

import com.devmind.knowledge.dto.EntryView;
import java.util.List;

/**
 * 注入 CLAUDE.md 的组装工具（preview 与真实注入共用）。
 * 结构：全局经验（按标签命中）→ 项目经验 → 当前任务 → 项目原有 CLAUDE.md（保留追加）。
 */
public final class ClaudeMd {

    private ClaudeMd() {
    }

    /**
     * @param entries     已按「全局在前、项目在后」排序的条目
     * @param taskSpec    任务说明
     * @param origContent 项目原有 CLAUDE.md（可 null）
     * @return 完整注入内容
     */
    public static String assemble(List<EntryView> entries, String taskSpec, String origContent) {
        StringBuilder md = new StringBuilder();
        md.append("<!-- 由 Dev-Mind KnowledgeInjector 自动生成，请勿手改本文件开头；项目自有内容保留在下方追加 -->\n");

        List<EntryView> global = entries.stream().filter(e -> "global".equals(e.scope())).toList();
        List<EntryView> project = entries.stream().filter(e -> "project".equals(e.scope())).toList();

        appendSection(md, "通用经验（global）", global);
        appendSection(md, "项目经验（project）", project);

        md.append("\n---\n\n## 当前任务\n\n").append(taskSpec == null ? "" : taskSpec.strip()).append("\n");

        if (origContent != null && !origContent.isBlank()) {
            md.append("\n---\n\n## 项目原有 CLAUDE.md（保留）\n\n").append(origContent).append("\n");
        }
        return md.toString();
    }

    private static void appendSection(StringBuilder md, String title, List<EntryView> entries) {
        if (entries.isEmpty()) {
            return;
        }
        md.append("\n---\n\n## ").append(title).append("\n\n");
        for (EntryView e : entries) {
            md.append("### ").append(e.name()).append("\n\n")
                    .append(e.contentMd() == null ? "" : e.contentMd().strip()).append("\n");
        }
    }
}
