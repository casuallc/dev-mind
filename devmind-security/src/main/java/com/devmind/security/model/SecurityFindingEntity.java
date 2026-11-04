package com.devmind.security.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * security_findings 表（CAP-66 FR-04）：跨引擎统一发现模型。
 * fingerprint 按引擎分型（SCA=cve+构件坐标；SAST/secrets=ruleId+路径+行哈希），
 * 状态机 NEW/EXISTING/SUPPRESSED/FIXED 由基线 diff 与抑制规则驱动。
 */
@Entity
@Table(name = "security_findings", uniqueConstraints = {
        @UniqueConstraint(name = "uk_security_findings_scan_fp", columnNames = {"scan_id", "fingerprint"})
})
public class SecurityFindingEntity {

    public static final String NEW = "NEW";
    public static final String EXISTING = "EXISTING";
    public static final String SUPPRESSED = "SUPPRESSED";
    public static final String FIXED = "FIXED";

    public static final String ENGINE_SCA = "sca";
    public static final String ENGINE_SAST = "sast";
    public static final String ENGINE_SECRETS = "secrets";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    /** 冗余便于跨任务查询（抑制/历史轨迹/CVE 抑制匹配） */
    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;
    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(length = 16)
    private String engine;

    @Column(length = 64)
    private String fingerprint;

    /** SCA=CVE；SAST/secrets=ruleId */
    @Column(name = "rule_id", length = 128)
    private String ruleId;

    /** CRITICAL/HIGH/MEDIUM/LOW/INFO */
    @Column(length = 16)
    private String severity;

    @Column(length = 512)
    private String title;

    @Column(name = "file_path", length = 512)
    private String filePath;
    @Column(name = "line_no")
    private Integer lineNo;
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    /** 引擎原始字段（CVE/CVSS/CWE/依赖坐标/CPE 证据/EPSS·KEV/引入链/remediation；密钥已脱敏） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "detail_json", length = 16_777_216)
    private String detailJson;

    /** SCA：Maven scope（compile/test/provided…），降权标记依据 */
    @Column(name = "dep_scope", length = 16)
    private String depScope;

    /** SCA：是否直接依赖；null=非 SCA（Boolean 列禁 @ColumnDefault，getter 兜底） */
    @Column(name = "direct_dep")
    private Boolean directDep;

    @Column(length = 16)
    private String status = NEW;

    @Column(name = "first_seen_scan_id")
    private Long firstSeenScanId;
    @Column(name = "fixed_in_scan_id")
    private Long fixedInScanId;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "review_note", length = 16_777_216)
    private String reviewNote;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "review_session_id", length = 64)
    private String reviewSessionId;
    @Column(name = "fix_session_id", length = 64)
    private String fixSessionId;
    @Column(name = "fix_diff_summary", length = 512)
    private String fixDiffSummary;

    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScanId() { return scanId; }
    public void setScanId(Long scanId) { this.scanId = scanId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getDepScope() { return depScope; }
    public void setDepScope(String depScope) { this.depScope = depScope; }
    public Boolean getDirectDep() { return directDep; }
    public void setDirectDep(Boolean directDep) { this.directDep = directDep; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getFirstSeenScanId() { return firstSeenScanId; }
    public void setFirstSeenScanId(Long firstSeenScanId) { this.firstSeenScanId = firstSeenScanId; }
    public Long getFixedInScanId() { return fixedInScanId; }
    public void setFixedInScanId(Long fixedInScanId) { this.fixedInScanId = fixedInScanId; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewSessionId() { return reviewSessionId; }
    public void setReviewSessionId(String reviewSessionId) { this.reviewSessionId = reviewSessionId; }
    public String getFixSessionId() { return fixSessionId; }
    public void setFixSessionId(String fixSessionId) { this.fixSessionId = fixSessionId; }
    public String getFixDiffSummary() { return fixDiffSummary; }
    public void setFixDiffSummary(String fixDiffSummary) { this.fixDiffSummary = fixDiffSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
