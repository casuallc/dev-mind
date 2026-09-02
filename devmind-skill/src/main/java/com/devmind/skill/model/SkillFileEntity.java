package com.devmind.skill.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * skill_files 表：skill 包的附属文件（references/scripts 等，相对路径）。
 * SKILL.md 本体不在此表（存 SkillEntity.contentMd），path 保留名 SKILL.md 由 Service 拦截。
 * 文本/二进制双 Lob：binary=true 时 contentBytes 有效，否则 contentText 有效；REST 统一 Base64 传输。
 * size 冗余字节数，列表查询不读 Lob。
 */
@Entity
@Table(name = "skill_files",
        uniqueConstraints = @UniqueConstraint(columnNames = {"skill_id", "path"}),
        indexes = @Index(name = "idx_skill_files_skill", columnList = "skill_id"))
public class SkillFileEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(name = "skill_id", nullable = false, length = 32)
    private String skillId;

    /** 包内相对路径（"/" 分隔，如 references/xxx.md、scripts/run.sh） */
    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private boolean binary;

    @Lob
    private String contentText;

    @Lob
    private byte[] contentBytes;

    /** 展示用（text/markdown、text/x-sh、image/png…），可空 */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /** 字节数（Base64 解码后的原始大小） */
    private long size;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public boolean isBinary() { return binary; }
    public void setBinary(boolean binary) { this.binary = binary; }
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
    public byte[] getContentBytes() { return contentBytes; }
    public void setContentBytes(byte[] contentBytes) { this.contentBytes = contentBytes; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
