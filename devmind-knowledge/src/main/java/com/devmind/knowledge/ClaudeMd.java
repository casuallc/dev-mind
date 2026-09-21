package com.devmind.knowledge;

import com.devmind.knowledge.dto.EntryView;
import java.util.List;

/**
 * 知识注入内容的组装工具：现仅用于 <b>预览</b>（真实注入由 CAP-33 装配管线经
 * {@code ContextMaterializer} 写入会话 worktree 的 CLAUDE.local.md，不再内联仓库自带 CLAUDE.md）。
 * 结构：全局经验（按标签命中）→ 项目经验 → 当前任务。
 */
public final class ClaudeMd {

    private ClaudeMd() {
    }

    /**
     * @param entries     已按「全局在前、项目在后」排序的条目
     * @param taskSpec    任务说明
     * @param origContent 历史遗留参数（CAP-34 起平台不再内联仓库 CLAUDE.md，调用方传 null；
     *                    仓库自带 CLAUDE.md 由 claude CLI 原生加载）
     * @return 完整预览内容
     */
    public static String assemble(List<EntryView> entries, String taskSpec, String origContent) {
        StringBuilder md = new StringBuilder();
        md.append("<!-- 由 Dev-Mind 知识注入预览生成（实际注入为会话 worktree 的 CLAUDE.local.md） -->\n");
        md.append(renderEntrySections(entries));
        md.append("\n---\n\n## 当前任务\n\n").append(taskSpec == null ? "" : taskSpec.strip()).append("\n");
        if (origContent != null && !origContent.isBlank()) {
            md.append("\n---\n\n## 项目原有 CLAUDE.md（保留）\n\n").append(origContent).append("\n");
        }
        return md.toString();
    }

    /**
     * 只渲染条目分节（CAP-33 装配管线用：节序与「当前任务」节由 assembler 统一编排）：
     * 通用经验（global）→ 项目经验（project），每节带 "\n---\n\n## " 分隔头；无条目返回空串。
     */
    public static String renderEntrySections(List<EntryView> entries) {
        StringBuilder md = new StringBuilder();
        List<EntryView> global = entries.stream().filter(e -> "global".equals(e.scope())).toList();
        List<EntryView> project = entries.stream().filter(e -> "project".equals(e.scope())).toList();
        appendSection(md, "通用经验（global）", global);
        appendSection(md, "项目经验（project）", project);
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
