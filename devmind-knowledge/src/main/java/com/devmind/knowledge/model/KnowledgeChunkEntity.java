package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 检索分块（CAP-44 FR-04，CAP-84 升级）：结构化切分，正文存 DB 供高亮/证据快照；
 * 向量由 Lucene 承载（CAP-84 起 embedding CLOB 停写，保留列仅兼容回滚/存量数据）。
 */
@Entity
@Table(name = "knowledge_chunks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"kb_id", "entry_id", "chunk_index"}),
        indexes = @Index(name = "idx_knowledge_chunks_kb", columnList = "kb_id"))
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long releaseId;
    private int releaseChunkIndex;
    @Lob @JdbcTypeCode(SqlTypes.LONGVARCHAR) private String applicabilityJson;
    public String getApplicabilityJson(){return applicabilityJson;}public void setApplicabilityJson(String value){applicabilityJson=value;}
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long v) { releaseId=v; }
    public int getReleaseChunkIndex() { return releaseChunkIndex; }
    public void setReleaseChunkIndex(int v) { releaseChunkIndex=v; }

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String content;

    /** 历史列：JSON float 数组。CAP-84 起停写（向量在 Lucene），仅存量数据保留 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String embedding;

    private int tokenCount;

    /** CAP-84：所属标题路径（如 "部署 > 回滚"），检索上下文与高亮定位用 */
    @Column(length = 512)
    private String headingPath;

    /** 原文字符偏移 [start, end)，前端可跳原文 */
    @ColumnDefault("0")
    private int startOffset;
    @ColumnDefault("0")
    private int endOffset;

    @Column(length = 64)
    private String chunkHash;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
    public String getHeadingPath() { return headingPath; }
    public void setHeadingPath(String headingPath) { this.headingPath = headingPath; }
    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int startOffset) { this.startOffset = startOffset; }
    public int getEndOffset() { return endOffset; }
    public void setEndOffset(int endOffset) { this.endOffset = endOffset; }
    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }
}
