package com.devmind.classify.pkg.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

/**
 * CAP-57 FR-03 包到节点的安装记录（{@code classify_package_installs}）：一行 = 「某包装到
 * 某节点」的一次分发状态。状态机 PENDING →（pkg_ack ok）→ INSTALLED /（ack 失败、断连）
 * → FAILED（可重试，重试复用同一行）。
 *
 * <p>{@link #installDir} 记节点侧<b>绝对路径</b>（pkg_ack 带回，展示与 checkpoint 登记复制用）；
 * 组 proc 帧用的相对路径不走这里——约定就是 {@code packages/pkg-<packageId>}（相对节点
 * {@code <workspaceRoot>/classify/} 根），见 {@code ClassifyInstanceService}。</p>
 */
@Entity
@Table(name = "classify_package_installs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"package_id", "node_id"}))
public class ClassifyPackageInstallEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_INSTALLED = "INSTALLED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "package_id", nullable = false)
    private Long packageId;

    /** 目标节点 id（字符串口径） */
    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    /** 节点侧安装目录绝对路径（pkg_ack 带回；INSTALLED 后才有值） */
    @Column(name = "install_dir", length = 512)
    private String installDir;

    /** PENDING / INSTALLED / FAILED */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private String status = STATUS_PENDING;

    /** 本次分发的 pkg 帧 requestId（排错血缘：installs 行 ↔ runner 日志） */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(length = 1024)
    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPackageId() { return packageId; }
    public void setPackageId(Long packageId) { this.packageId = packageId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getInstallDir() { return installDir; }
    public void setInstallDir(String installDir) { this.installDir = installDir; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
