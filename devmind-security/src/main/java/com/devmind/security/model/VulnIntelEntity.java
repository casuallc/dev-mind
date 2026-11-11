package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * vuln_intel 表（CAP-66 FR-10-4/FR-10-5）：EPSS/KEV/NVD 情报快照，CVE 主键 upsert。
 * 服务端定时同步 FIRST.org EPSS CSV、CISA KEV JSON 与 NVD API 2.0 元数据（发布时间/修改时间/
 * 描述/CVSS）；数据源不可达降级为查无记录（字段 null），不阻塞扫描。kev 用实体初始值（Boolean 列禁 @ColumnDefault）。
 */
@Entity
@Table(name = "vuln_intel")
public class VulnIntelEntity {

    @Id
    @Column(length = 32)
    private String cve;

    @Column(name = "epss_score")
    private Double epssScore;

    @Column(name = "epss_percentile")
    private Double epssPercentile;

    @Column(nullable = false)
    private boolean kev = false;

    @Column(name = "kev_due_date", length = 16)
    private String kevDueDate;

    /** CVE 描述（NVD en 摘要 / KEV shortDescription 兜底，截断 2000 字符；varchar 非 Lob 便于模糊查询） */
    @Column(length = 2048)
    private String description;

    /** NVD 发布时间（CVE 自身生命周期，与 updatedAt 同步时间区分） */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** NVD 最后修改时间 */
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    /** CVSS 基础分（v3.1 > v3.0 > v2 优先序） */
    @Column(name = "cvss_score")
    private Double cvssScore;

    /** CVSS 严重度（CRITICAL/HIGH/MEDIUM/LOW/NONE） */
    @Column(name = "cvss_severity", length = 16)
    private String cvssSeverity;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getCve() { return cve; }
    public void setCve(String cve) { this.cve = cve; }
    public Double getEpssScore() { return epssScore; }
    public void setEpssScore(Double epssScore) { this.epssScore = epssScore; }
    public Double getEpssPercentile() { return epssPercentile; }
    public void setEpssPercentile(Double epssPercentile) { this.epssPercentile = epssPercentile; }
    public boolean isKev() { return kev; }
    public void setKev(boolean kev) { this.kev = kev; }
    public String getKevDueDate() { return kevDueDate; }
    public void setKevDueDate(String kevDueDate) { this.kevDueDate = kevDueDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
    public void setLastModifiedAt(Instant lastModifiedAt) { this.lastModifiedAt = lastModifiedAt; }
    public Double getCvssScore() { return cvssScore; }
    public void setCvssScore(Double cvssScore) { this.cvssScore = cvssScore; }
    public String getCvssSeverity() { return cvssSeverity; }
    public void setCvssSeverity(String cvssSeverity) { this.cvssSeverity = cvssSeverity; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
