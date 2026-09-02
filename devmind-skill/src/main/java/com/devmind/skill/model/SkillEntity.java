package com.devmind.skill.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * skills 表（Skill 管理，基础模块）：Claude Code skill 包本体。
 * name/description 对应 SKILL.md frontmatter 的两个必填键（拆为结构化字段便于唯一约束与检索），
 * contentMd 只存 frontmatter 之后的正文，其余 frontmatter 键 JSON 序列化存 extraFrontmatter，
 * 导出时由 SkillService 拼回完整 SKILL.md。
 *
 * 注意：GLOBAL 行的 project_id 统一存空串 ""（而非 null）——H2 唯一约束中 NULL 互不相等，
 * 空串才能让 (scope, project_id, name) 唯一约束对 GLOBAL 同样生效。View 层转 null 展示。
 */
@Entity
@Table(name = "skills", uniqueConstraints = @UniqueConstraint(columnNames = {"scope", "project_id", "name"}))
public class SkillEntity {

    /** 作用域：GLOBAL 平台共享 / PROJECT 项目私有 */
    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_PROJECT = "PROJECT";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    /** GLOBAL 行的 projectId 落库值（见类注释） */
    public static final String GLOBAL_PROJECT_ID = "";

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 16)
    private String scope;

    /** PROJECT 必填；GLOBAL 存 ""（非 null，见类注释） */
    @Column(name = "project_id", length = 32)
    private String projectId;

    /** skill 名 = SKILL.md frontmatter name = .claude/skills/&lt;name&gt;/ 目录名，kebab-case */
    @Column(nullable = false, length = 64)
    private String name;

    /** SKILL.md frontmatter description（注入匹配的 Trigger 依据，必填） */
    @Column(length = 500)
    private String description;

    /** SKILL.md 正文（frontmatter 之后部分） */
    @Lob
    private String contentMd;

    /** 其余 frontmatter 键的 JSON（如 allowed-tools），导出时原样拼回 */
    @Lob
    private String extraFrontmatter;

    /** 标签，逗号拼接存储；为后续按项目匹配注入预留 */
    @Column(length = 500)
    private String tags;

    @Column(length = 16)
    @ColumnDefault(STATUS_ACTIVE)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    /** 预留：注入计数（对照 knowledge 条目 hitCount） */
    @Column(name = "hit_count")
    private int hitCount;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getExtraFrontmatter() { return extraFrontmatter; }
    public void setExtraFrontmatter(String extraFrontmatter) { this.extraFrontmatter = extraFrontmatter; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    /** 旧行可能为 null（ddl-auto 历史数据），统一兜底 ACTIVE */
    public String getStatus() { return status == null ? STATUS_ACTIVE : status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
