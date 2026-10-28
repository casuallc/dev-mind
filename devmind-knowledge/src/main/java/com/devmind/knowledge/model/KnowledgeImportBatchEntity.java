package com.devmind.knowledge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * CAP-84 FR-07 导入批次：一次上传（文件/文件夹/ZIP）对应一条，汇总逐文件结果。
 * id 由服务端生成（UUID），同时作为 staging 目录名与任务 payload。
 */
@Entity
@Table(name = "knowledge_import_batches")
public class KnowledgeImportBatchEntity {
    /** Trusted submitting/retrying identity; legacy caller-supplied createdBy is not authorization. */
    private String executionActor;
    public String getExecutionActor() { return executionActor; }
    public void setExecutionActor(String actor) { executionActor = actor; }

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private Long kbId;

    @Column(length = 64)
    private String taskId;

    @Column(length = 255)
    private String name;

    @Column(length = 16)
    private String defaultDocType;

    /** 批次追加标签（逗号分隔，与目录派生标签合并） */
    @Column(length = 1000)
    private String tags;

    @Column(length = 16)
    private String status = STATUS_RUNNING;

    private int totalFiles;
    private int successCount;
    private int failedCount;
    private int skippedCount;

    @Column(length = 1000)
    private String error;

    @Column(length = 64)
    private String createdBy;

    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefaultDocType() { return defaultDocType; }
    public void setDefaultDocType(String defaultDocType) { this.defaultDocType = defaultDocType; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
