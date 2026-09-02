package com.devmind.skill.dto;

import java.util.List;

/**
 * Skill 包导出视图（为注入 agent 工作目录预留）：每个 skill 一棵完整文件树。
 * files[0] 固定为 "SKILL.md"（服务端拼好 frontmatter + 正文），其后为附件原相对路径。
 * 调用方按 name 落盘 &lt;worktree&gt;/.claude/skills/&lt;name&gt;/ 即可被 Claude Code 直接识别。
 */
public record SkillPackageView(List<SkillPackageItem> items) {

    public record SkillPackageItem(
            String skillId,
            String name,
            String scope,
            String projectId,
            List<ExportedFile> files) {
    }

    /** 内容统一 Base64；binary=false 时解码即 UTF-8 文本 */
    public record ExportedFile(
            String path,
            boolean binary,
            String contentBase64) {
    }
}
