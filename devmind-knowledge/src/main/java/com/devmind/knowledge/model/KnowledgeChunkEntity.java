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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 检索分块（CAP-44 FR-04）：条目内容按递归分隔符切分 + embedding 向量化。
 * 向量存 JSON float 数组 CLOB（不依赖 pgvector，H2/PG 双库通用），检索时 Java 内存余弦。
 * 条目重索引 = 删旧写新（kb_id+entry_id+chunk_index 唯一约束兜底并发重入）。
 */
@Entity
@Table(name = "knowledge_chunks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"kb_id", "entry_id", "chunk_index"}),
        indexes = @Index(name = "idx_knowledge_chunks_kb", columnList = "kb_id"))
public class KnowledgeChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    /** JSON float 数组（如 [0.012,-0.034,...]），维度 = 配置 dimensions */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String embedding;

    private int tokenCount;

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
}
