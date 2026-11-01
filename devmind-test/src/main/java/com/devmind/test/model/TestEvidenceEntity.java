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
 * CAP-104 test_evidence：只追加的运行证据。每条证据存 sha256 与前一条哈希（prev_hash）
 * 形成运行级哈希链；不提供更新/删除 API。
 */
@Entity
@Table(name = "test_evidence")
public class TestEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** M2 方案父运行 id（M1 为空） */
    @Column(name = "plan_run_id")
    private Long planRunId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    /** QUEUE_LOG / CONSOLE_LOG / STDOUT / STDERR / JUNIT_XML / SCREENSHOT / RESULT_JSON / CLEANUP_LOG */
    @Column(length = 32)
    private String kind;

    @Column(length = 256)
    private String filename;

    @Column(length = 64)
    private String sha256;

    /** 运行内前一条证据的 sha256（首条为空串） */
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "storage_ref", length = 16_777_216)
    private String storageRef;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** 运行内顺序号（1 起），与 hash 链一起保证可校验 */
    @Column(name = "seq_no")
    private Integer seqNo;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanRunId() { return planRunId; }
    public void setPlanRunId(Long planRunId) { this.planRunId = planRunId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getStorageRef() { return storageRef; }
    public void setStorageRef(String storageRef) { this.storageRef = storageRef; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
