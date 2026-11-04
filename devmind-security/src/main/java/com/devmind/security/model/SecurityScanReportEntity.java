package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * security_scan_reports 表（CAP-66 FR-03/06）：原始报告 → CAP-32 附件映射。
 * uk(scan_id, engine, format) 幂等覆盖——同引擎同格式重传删旧附件换新。
 */
@Entity
@Table(name = "security_scan_reports", uniqueConstraints = {
        @UniqueConstraint(name = "uk_security_scan_report", columnNames = {"scan_id", "engine", "format"})
})
public class SecurityScanReportEntity {

    public static final String FORMAT_JSON = "json";
    public static final String FORMAT_HTML = "html";
    public static final String FORMAT_SARIF = "sarif";
    public static final String FORMAT_DEPTREE = "deptree";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(nullable = false, length = 16)
    private String engine;

    /** json / html / sarif / deptree */
    @Column(nullable = false, length = 16)
    private String format;

    @Column(name = "attachment_id", nullable = false, length = 64)
    private String attachmentId;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScanId() { return scanId; }
    public void setScanId(Long scanId) { this.scanId = scanId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
