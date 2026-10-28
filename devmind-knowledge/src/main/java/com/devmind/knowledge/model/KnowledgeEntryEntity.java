package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 知识条目（CAP-04 FR-02/FR-03，CAP-44 重构）：归属知识库（kbId）。CAP-94 起库不再分
 * 全局/项目层，scope/projectId 历史列保留停写（防 rollback 丢数据）。
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "knowledge_entries")
public class KnowledgeEntryEntity {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_FEISHU = "feishu";
    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_GIT = "git";
    public static final String SOURCE_API = "api";
    public static final String DOC_TECH = "TECH";
    public static final String DOC_MAINT = "MAINT";
    public static final String DOC_FAQ = "FAQ";
    public static final String DOC_ARTIFACT = "ARTIFACT";
    public static final String INDEX_PENDING = "pending";
    public static final String INDEX_READY = "ready";
    public static final String INDEX_FAILED = "failed";
    /** 向量通道未配置；CAP-84 起文本仍进 Lucene BM25，检索可用 */
    public static final String INDEX_DISABLED = "disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @jakarta.persistence.Version @ColumnDefault("0") private long rowRevision;

    /** 归属知识库（CAP-44；存量行由 KnowledgeBaseMigration 回填） */
    private Long kbId;

    /** CAP-95: optional restriction, evaluated in addition to the base policy. */
    private Long policyId;
    @org.hibernate.annotations.ColumnDefault("0")
    private long releaseSetRevision;
    private Instant indexedAt;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String parseReportJson;
    public String getParseReportJson(){return parseReportJson;}public void setParseReportJson(String v){parseReportJson=v;}
    public Instant getIndexedAt(){return indexedAt;} public void setIndexedAt(Instant v){indexedAt=v;}
    public long getReleaseSetRevision() { return releaseSetRevision; }
    public void advanceReleaseSet() { releaseSetRevision++; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long policyId) { this.policyId = policyId; }

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

    /** 来源：manual | feishu | file | git | api */
    @Column(length = 16)
    private String source = SOURCE_MANUAL;

    /** CAP-84 FR-01：文档类型 TECH/MAINT/FAQ/ARTIFACT（标签做主题，不再建目录树） */
    @Column(length = 16)
    private String docType = DOC_TECH;

    /** 原始相对路径/外部地址（本地导入用于判重与溯源） */
    @Column(length = 1024)
    private String sourceUri;

    /** 外部版本（commit/文档版本号等） */
    @Column(length = 128)
    private String sourceVersion;

    @Column(length = 128)
    private String mimeType;

    /** 文档摘要（默认取正文前 300 字，后续可被模型摘要覆盖） */
    @Column(length = 2000)
    private String summary;

    @Column(length = 16)
    private String language = "zh";

    /** 负责人（导入批次发起人/手工录入人） */
    @Column(length = 64)
    private String owner;

    /** 内容版本（每次正文变化 +1；历史见 knowledge_entry_versions） */
    @ColumnDefault("1")
    private int contentVersion = 1;

    private Instant validFrom;
    private Instant validTo;
    private Instant lastReviewedAt;

    /** 扩展元数据（JSON，暂不参与检索） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String metadataJson;

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

    /** CAP-85 FR-27：外部同步源归属（手工/本地上传为空） */
    private Long syncSourceId;

    /** CAP-85 FR-29：正文 SimHash（十六进制 16 位），检索索引时维护，重复检测用 */
    @Column(length = 16)
    private String simHash;

    /** CAP-85 FR-28：是否允许模型自动补充分类/摘要/标签；人工修正后置 false */
    private Boolean autoEnrich = Boolean.TRUE;

    /** 最近一次模型补全成功时间；正文变化时清空 */
    private Instant enrichedAt;

    /** 最近一次模型补全失败原因（不阻断摄入） */
    @Column(length = 1000)
    private String enrichError;

    /**
     * CAP-48 FR-06 索引血缘：本条目的向量是用哪个端点、哪个模型、多少维建的。
     * 三者对齐才说明索引仍有效——换模型/换维度后旧向量与新查询向量维度不等，
     * 余弦恒 0 被阈值全部过滤，旧行为只表现为"搜不到"（静默空结果）。
     */
    @Column(name = "indexed_endpoint_id")
    private Long indexedEndpointId;

    @Column(name = "indexed_model", length = 128)
    private String indexedModel;

    @Column(name = "indexed_dimensions")
    private Integer indexedDimensions;

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
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public int getContentVersion() { return contentVersion; }
    public void setContentVersion(int contentVersion) { this.contentVersion = contentVersion; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }
    public Instant getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(Instant lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public String getIndexError() { return indexError; }
    public void setIndexError(String indexError) { this.indexError = indexError; }
    public Long getSyncSourceId() { return syncSourceId; }
    public void setSyncSourceId(Long syncSourceId) { this.syncSourceId = syncSourceId; }
    public String getSimHash() { return simHash; }
    public void setSimHash(String simHash) { this.simHash = simHash; }
    public Boolean getAutoEnrich() { return autoEnrich; }
    public void setAutoEnrich(Boolean autoEnrich) { this.autoEnrich = autoEnrich; }
    public Instant getEnrichedAt() { return enrichedAt; }
    public void setEnrichedAt(Instant enrichedAt) { this.enrichedAt = enrichedAt; }
    public String getEnrichError() { return enrichError; }
    public void setEnrichError(String enrichError) { this.enrichError = enrichError; }
    public Long getIndexedEndpointId() { return indexedEndpointId; }
    public void setIndexedEndpointId(Long indexedEndpointId) { this.indexedEndpointId = indexedEndpointId; }
    public String getIndexedModel() { return indexedModel; }
    public void setIndexedModel(String indexedModel) { this.indexedModel = indexedModel; }
    public Integer getIndexedDimensions() { return indexedDimensions; }
    public void setIndexedDimensions(Integer indexedDimensions) { this.indexedDimensions = indexedDimensions; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
