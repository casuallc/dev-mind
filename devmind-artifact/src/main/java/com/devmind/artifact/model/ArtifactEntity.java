package com.devmind.artifact.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * artifacts 表（P1-2 Artifact 服务）：构建产物的一等实体，替代 build 记录里的
 * artifactRef 字符串（该字段保留作兼容展示，本表为准）。
 * 由 build 登记，deploy/test/release 消费（按 id 或 producerJob 反查）。
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    /** FILE / MAVEN / IMAGE / PACKAGE */
    public static final String TYPE_FILE = "FILE";

    /** LOCAL / S3（存储 SPI 类型，见 ArtifactStorage） */
    public static final String STORAGE_LOCAL = "LOCAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** P0-6 关联约定：可空 = 项目级产物 */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    /** FILE / MAVEN / IMAGE / PACKAGE */
    @Column(nullable = false, length = 32)
    private String type;

    /** 制品名（如 jar 文件名 / 镜像名） */
    @Column(length = 256)
    private String name;

    @Column(length = 128)
    private String version;

    /** 内容校验（sha256:…），构建脚本可通过 artifact_checksum= 上报 */
    @Column(length = 128)
    private String checksum;

    /** LOCAL / S3 */
    @Column(nullable = false, length = 16)
    private String storage;

    /** 存储内定位：本地路径 / 对象 key / 镜像 tag */
    @Column(nullable = false, length = 512)
    private String path;

    /** 生产者类型：BUILD（后续 RELEASE 等） */
    @Column(name = "producer_type", length = 24)
    private String producerType;

    /** 生产者记录 id（如 buildId） */
    @Column(name = "producer_id")
    private Long producerId;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getProducerType() { return producerType; }
    public void setProducerType(String producerType) { this.producerType = producerType; }
    public Long getProducerId() { return producerId; }
    public void setProducerId(Long producerId) { this.producerId = producerId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
