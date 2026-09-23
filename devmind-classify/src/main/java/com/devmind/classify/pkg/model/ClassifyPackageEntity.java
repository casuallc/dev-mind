package com.devmind.classify.pkg.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * CAP-57 FR-03 分类安装包（{@code classify_packages}）：一行 = 一份上传到平台、可分发给
 * 节点的 zip 包。三类：{@link #KIND_SIDECAR_APP}（边车代码+依赖）、{@link #KIND_MODEL_WEIGHTS}
 * （模型权重，可达 GB）、{@link #KIND_CORPUS}（语料/数据）。
 *
 * <p>包文件落在服务端 {@code devmind.classify.storage-dir} 下（按 id 命名，原始文件名仅展示），
 * 节点经 {@code GET /api/agent/classify/packages/{id}?token=} 拉取——「节点拉取」模式，
 * 平台不建推送通道。{@link #sha256} 是分发一致性的唯一凭据（runner 下载后校验，不符即失败）。</p>
 */
@Entity
@Table(name = "classify_packages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "name", "pkg_version"}))
public class ClassifyPackageEntity {

    /** 边车程序包（代码 + 依赖，实例的应用包） */
    public static final String KIND_SIDECAR_APP = "SIDECAR_APP";
    /** 模型权重包（checkpoint；实例 env 里以 ${PKG_DIR:<id>} 引用） */
    public static final String KIND_MODEL_WEIGHTS = "MODEL_WEIGHTS";
    /** 语料/数据包 */
    public static final String KIND_CORPUS = "CORPUS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SIDECAR_APP / MODEL_WEIGHTS / CORPUS */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 128)
    private String name;

    /** 版本串（列名 pkg_version：version 是 H2 保留字红线） */
    @Column(name = "pkg_version", nullable = false, length = 128)
    private String pkgVersion;

    /** 文件 sha256（64 位 hex；runner 下载校验 + 「就是这一份」的凭据） */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** 上传时的原始文件名（展示用；存储按 id 命名防路径注入） */
    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    /** 服务端存储路径（绝对路径，本机路径红线例外：这是运行时数据不是配置，与附件存储同口径） */
    @Column(name = "stored_path", nullable = false, length = 512)
    private String storedPath;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPkgVersion() { return pkgVersion; }
    public void setPkgVersion(String pkgVersion) { this.pkgVersion = pkgVersion; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
