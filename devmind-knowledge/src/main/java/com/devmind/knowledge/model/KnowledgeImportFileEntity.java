package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * CAP-84 FR-07 批次内逐文件状态（解析/去重/索引失败可见，可单条重试）。
 * storedPath 指向已落盘的原始文件，任务重试与失败重试不再依赖上传临时目录。
 */
@Entity
@Table(name = "knowledge_import_files",
        indexes = @Index(name = "idx_knowledge_import_files_batch", columnList = "batch_id"))
public class KnowledgeImportFileEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PARSING = "parsing";
    public static final String STATUS_INDEXED = "indexed";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    @Column(nullable = false, length = 1024)
    private String relativePath;

    @Column(length = 1024)
    private String storedPath;

    @Column(length = 255)
    private String fileName;

    @Column(length = 128)
    private String mimeType;

    private long sizeBytes;

    @Column(length = 64)
    private String contentHash;

    private Long entryId;

    @Column(length = 16)
    private String status = STATUS_PENDING;

    @Column(length = 1000)
    private String error;

    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
