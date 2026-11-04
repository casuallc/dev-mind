package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * vuln_intel 表（CAP-66 FR-10-4）：EPSS/KEV 情报快照，CVE 主键 upsert。
 * 服务端定时同步 FIRST.org EPSS CSV 与 CISA KEV JSON；数据源不可达降级为查无记录（字段 null），
 * 不阻塞扫描。kev 用实体初始值（Boolean 列禁 @ColumnDefault）。
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
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
