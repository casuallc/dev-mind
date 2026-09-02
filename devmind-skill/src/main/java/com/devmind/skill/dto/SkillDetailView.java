package com.devmind.skill.dto;

import java.util.List;
import java.util.Map;

/** Skill 详情视图：列表视图 + SKILL.md 正文 + 其余 frontmatter 键 + 附件元数据。 */
public record SkillDetailView(
        SkillView skill,
        String contentMd,
        Map<String, String> extraFrontmatter,
        List<SkillFileView> files) {
}
