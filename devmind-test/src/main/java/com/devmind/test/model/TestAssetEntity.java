package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-104 test_assets：测试资产（测试程序/调度脚本包）。
 * kind=GIT 保留 repoUrl+branch+commit；ZIP/FILE 保存 sha256 与解包存储引用。
 * 未评审（reviewStatus != APPROVED）资产不可被方案引用或执行。
 */
@Entity
@Table(name = "test_assets")
public class TestAssetEntity {

    public static final String GIT = "GIT";
    public static final String ZIP = "ZIP";
    public static final String FILE = "FILE";

    /** 资产角色：DATA = 普通脚本/数据资产；PROGRAM = M1.5 预构建程序包 */
    public static final String ROLE_DATA = "DATA";
    public static final String ROLE_PROGRAM = "PROGRAM";

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 128)
    private String name;

    /** GIT: repoUrl；ZIP/FILE: 原文件名或来源说明 */
    @Column(length = 1024)
    private String source;

    @Column(length = 128)
    private String branch;

    @Column(name = "commit_hash", length = 64)
    private String commitHash;

    @Column(length = 64)
    private String sha256;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** ZIP 解包文件数；单文件为 1，GIT 为空 */
    @Column(name = "file_count")
    private Integer fileCount;

    /** 本地存储引用（相对证据/资产根目录；GIT 为空） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "storage_ref", length = 16_777_216)
    private String storageRef;

    @Column(name = "review_status", length = 16)
    private String reviewStatus;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(length = 512)
    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    // ---------------- CAP-104 M1.5 程序包 ----------------

    /** DATA / PROGRAM */
    @Column(name = "asset_role", length = 16)
    private String assetRole;

    /** PROGRAM 清单：JAVA / NATIVE / NODE / PYTHON / SHELL */
    @Column(length = 16)
    private String runtime;

    /** PROGRAM 包内入口脚本（相对包根，如 run.sh） */
    @Column(length = 512)
    private String entrypoint;

    @Column(name = "target_os", length = 16)
    private String targetOs;

    @Column(name = "target_arch", length = 16)
    private String targetArch;

    /** PROGRAM 原始清单 JSON（执行前复核 + 运行详情展示） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "manifest_json", length = 16_777_216)
    private String manifestJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommitHash() { return commitHash; }
    public void setCommitHash(String commitHash) { this.commitHash = commitHash; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getAssetRole() { return assetRole; }
    public void setAssetRole(String assetRole) { this.assetRole = assetRole; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public String getEntrypoint() { return entrypoint; }
    public void setEntrypoint(String entrypoint) { this.entrypoint = entrypoint; }
    public String getTargetOs() { return targetOs; }
    public void setTargetOs(String targetOs) { this.targetOs = targetOs; }
    public String getTargetArch() { return targetArch; }
    public void setTargetArch(String targetArch) { this.targetArch = targetArch; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
}
