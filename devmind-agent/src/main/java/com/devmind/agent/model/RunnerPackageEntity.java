package com.devmind.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * runner_packages 表：服务端托管的 agent-runner 升级包（CAP-21 FR-09）。
 * 全局单份，固定 id=1 覆盖式 upsert——上传即替换；jar 本体落盘 data/agent-runner/runner.jar。
 */
@Entity
@Table(name = "runner_packages")
public class RunnerPackageEntity {

    /** 恒 1L（单行表，不用 IDENTITY） */
    @Id
    private Long id;

    /** 包内 runner-version.txt 提取的版本号 */
    @Column(nullable = false, length = 64)
    private String version;

    /** jar 字节 SHA-256（hex 64），升级时下发给 runner 校验 */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_filename", length = 256)
    private String originalFilename;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
}
