package com.devmind.skill.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Skill 创建/更新请求。scope/projectId 仅创建时生效（更新忽略，作用域不可变）。
 * contentMd 只含 SKILL.md 正文（frontmatter 之后）；extraFrontmatter 为其余 frontmatter 键，
 * name/description 两键由结构化字段承载，传入也会被剔除。
 */
public record SkillRequest(
        /** GLOBAL | PROJECT */
        @NotBlank String scope,
        /** scope=PROJECT 必填；GLOBAL 忽略 */
        String projectId,
        /** kebab-case，作为 .claude/skills/<name>/ 目录名 */
        @NotBlank String name,
        /** SKILL.md frontmatter description，必填 */
        @NotBlank String description,
        String contentMd,
        Map<String, String> extraFrontmatter,
        List<String> tags,
        /** ACTIVE | DISABLED，空则 ACTIVE（创建）或不变（更新） */
        String status) {
}
