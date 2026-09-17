package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 知识条目（CAP-04 FR-02/FR-03，CAP-44 重构）：归属知识库（kbId），库的 scope 决定
 * 全局/项目语义。历史 scope/projectId 列保留停写（防 rollback 丢数据），读取一律经 KB 派生。
 */
@Entity
@Table(name = "knowledge_entries")
public class KnowledgeEntryEntity {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_FEISHU = "feishu";
    public static final String INDEX_PENDING = "pending";
    public static final String INDEX_READY = "ready";
    public static final String INDEX_FAILED = "failed";
    /** embedding 未配置：不走向量索引，检索退化 LIKE */
    public static final String INDEX_DISABLED = "disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属知识库（CAP-44；存量行由 KnowledgeBaseMigration 回填） */
    private Long kbId;

    /** 历史列：global | project（CAP-44 起停写，语义上移到 knowledge_bases.scope） */
    @Column(length = 16)
    private String scope;

    /** 历史列：project 范围所属项目（CAP-44 起停写，上移到 knowledge_bases.project_id） */
    @Column(length = 64)
    private String projectId;

    /** 条目名称（如"前端样式规范"） */
    @Column(length = 200)
    private String name;

    /** 逻辑路径（如 global/frontend/antd-styles.md），仅作组织展示 */
    @Column(length = 255)
    private String path;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String contentMd;

    /** 标签，逗号分隔（global 条目用于按项目 tags 匹配注入） */
    @Column(length = 500)
    private String tags;

    /** 来源项目（沉淀自哪个项目） */
    @Column(length = 64)
    private String sourceProject;

    /** 被注入次数（FR-07 清理依据） */
    private int hitCount;

    /** active | deprecated（FR-07） */
    @Column(length = 16)
    private String status;

    /** 来源：manual | feishu（CAP-45 写值） */
    @Column(length = 16)
    private String source = SOURCE_MANUAL;

    /** 外部系统主键（飞书 node token 等，CAP-45 判重） */
    @Column(length = 256)
    private String externalId;

    /** 内容哈希（CAP-45 重同步变更检测；手动保存也维护） */
    @Column(length = 64)
    private String contentHash;

    /** 向量索引状态：pending | ready | failed | disabled（embedding 未配置） */
    @Column(length = 16)
    private String indexStatus = INDEX_PENDING;

    /** 最近一次索引失败原因 */
    @Column(length = 1000)
    private String indexError;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSourceProject() { return sourceProject; }
    public void setSourceProject(String sourceProject) { this.sourceProject = sourceProject; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public String getIndexError() { return indexError; }
    public void setIndexError(String indexError) { this.indexError = indexError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
