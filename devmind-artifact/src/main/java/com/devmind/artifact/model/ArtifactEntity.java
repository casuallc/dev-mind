package com.devmind.artifact.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * artifacts 表（CAP-13 工作产物一等实体）：覆盖构建产物与信息类产物。
 * type = PACKAGE / DOC / CODE_DIFF / TEST_REPORT / REVIEW / ANALYSIS（FILE 保留兼容历史数据）；
 * producer_type = BUILD / SESSION / TEST_RUN / DOC / MANUAL；
 * 归属挂 work_item_id 或 requirement_id（分析产物直挂需求）；信息类产物无存储实体，
 * storage/path 可空，path 存引用（如 docId / sessionId）。
 * build 登记的 artifactRef 字符串字段保留作兼容展示，本表为准。
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    /** FILE（历史兼容）/ PACKAGE / DOC / CODE_DIFF / TEST_REPORT / REVIEW / ANALYSIS */
    public static final String TYPE_FILE = "FILE";
    public static final String TYPE_PACKAGE = "PACKAGE";
    public static final String TYPE_DOC = "DOC";
    public static final String TYPE_CODE_DIFF = "CODE_DIFF";
    public static final String TYPE_TEST_REPORT = "TEST_REPORT";
    public static final String TYPE_REVIEW = "REVIEW";
    public static final String TYPE_ANALYSIS = "ANALYSIS";

    /** LOCAL / S3（存储 SPI 类型，见 ArtifactStorage）；信息类产物可空 */
    public static final String STORAGE_LOCAL = "LOCAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    /** CAP-13 关联约定：工作单元 id（可空 = 项目级或需求级产物） */
    @Column(name = "work_item_id", length = 32)
    private String workItemId;

    /** CAP-13 关联约定：需求 id（分析产物直挂需求，可空） */
    @Column(name = "requirement_id", length = 32)
    private String requirementId;

    /** FILE / PACKAGE / DOC / CODE_DIFF / TEST_REPORT / REVIEW / ANALYSIS */
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

    /** LOCAL / S3；信息类产物（DOC/REVIEW/ANALYSIS 等）可空 */
    @Column(length = 16)
    private String storage;

    /** 存储内定位：本地路径 / 对象 key / 镜像 tag；信息类产物存引用（docId/sessionId 等） */
    @Column(length = 512)
    private String path;

    /** 生产者类型：BUILD / SESSION / TEST_RUN / DOC / MANUAL */
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
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
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
