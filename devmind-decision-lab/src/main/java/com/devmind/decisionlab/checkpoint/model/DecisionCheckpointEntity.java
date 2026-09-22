package com.devmind.decisionlab.checkpoint.model;

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
 * CAP-56 FR-06 决策模型产物登记（{@code decision_checkpoints}）：一行 = 一份可以被服务的模型权重
 * 及其来历。
 *
 * <p><b>权重留在节点上，库里只留指纹与指标</b>：一个 checkpoint 几百 MB 到几 GB，塞进库既不现实
 * 也没意义（服务它的边车在节点上读的是文件系统）。所以这里登记的是"它是谁"——
 * {@code source_path}（HF repo id 或节点上的绝对路径）+ 指纹（路径/字节数/sha256）
 * + 在哪个节点上 + 跑出来的指标。要复现或核对某份产物，靠的是指纹而不是"那天导出的那个文件"。</p>
 *
 * <p><b>{@link #verified} 是准入闸门的唯一开关</b>（FR-07）：{@code true} 才有资格让决策能力
 * 放行。它由人按下（跑过评测、看过报告、确认指标可用于自己的场景），不由系统自动置上——
 * "自动通过"的准入等于没有准入，而 2026-09-22 那次退化的教训正是"没人看过指标"。</p>
 *
 * <p>同一个 {@link #serveSlot} 上<b>最多只有一行</b> {@code verified}（服务方的保证由
 * {@code CheckpointService.verify} 维持）：边车一个槽位只加载一份权重，"哪个模型在放行"
 * 必须是唯一答案。</p>
 */
@Entity
@Table(name = "decision_checkpoints")
public class DecisionCheckpointEntity {

    /** 官方基础 checkpoint（HF 上直接拿的，没经过微调） */
    public static final String KIND_BASE = "BASE";
    /** RLCD 微调产物（CAP-56 FR-05 在节点上训出来的） */
    public static final String KIND_FINETUNED = "FINETUNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /**
     * 服务的槽位名（边车 {@code LAYA_SLOT_MODELS} 的键，如 {@code multilingual}）。
     * 列名 {@code serve_slot}：{@code slot} 在部分库上是函数名，加上前缀既避坑又更清楚。
     */
    @Column(name = "serve_slot", length = 64)
    private String serveSlot;

    /** BASE / FINETUNED */
    @Column(length = 16)
    private String kind;

    /** 权重来源：HF repo id（{@code org/model}）或节点上的绝对路径 */
    @Column(name = "source_path", length = 512)
    private String sourcePath;

    /** 权重所在的 runner 节点标识（CAP-21 的节点 id 字符串；与会话的 agent_node_id 同口径） */
    @Column(name = "node_id", length = 64)
    private String nodeId;

    /** 指纹：权重文件在节点上的路径（{@code config.json} 所在目录） */
    @Column(name = "fingerprint_path", length = 512)
    private String fingerprintPath;

    /** 指纹：权重字节数（对不上就是换了文件，哪怕 sha256 还没算） */
    @Column(name = "fingerprint_bytes")
    private Long fingerprintBytes;

    /** 指纹：文件 sha256（64 位十六进制；唯一能证明"就是这一份"的东西） */
    @Column(name = "fingerprint_sha256", length = 64)
    private String fingerprintSha256;

    /** 评测指标 JSON（CAP-56 FR-03 的官方口径报告；微调任务结束时的自动回评也写这里） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "metrics_json", length = 16_777_216)
    private String metricsJson;

    /** 温度校准参数 JSON（FR-04：{@code (题型, 选项数桶) → T}；没校准过为空） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "calibration_json", length = 16_777_216)
    private String calibrationJson;

    /**
     * 准入开关。Boolean 列<b>禁 {@code @ColumnDefault}</b>（红字：MySQL bit 列建不出来），
     * 走实体初始值 + getter 兜底，存量行由 {@code DecisionLabMigration} 补一次。
     */
    @Column(nullable = true)
    private Boolean verified = Boolean.FALSE;

    /**
     * 最近一次验证事件的记录人/时间/依据。
     *
     * <p>读的是"最后一次验证"而不是"此刻是否在放行"——那个看 {@link #verified}。放行权被撤销或被
     * 另一份产物顶掉（同槽位互斥）时这三个字段<b>保留</b>：它们回答的是"谁在什么时候凭什么验过它"，
     * 这份历史不因为后来失宠就作废（真删了的话，追责时只剩"它曾经绿过"）。</p>
     */
    @Column(name = "verified_by", length = 64)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** 人工确认时写下的判断依据（"评了多少条、哪个指标、和基线比如何"），事后追责靠它 */
    @Column(name = "verified_note", length = 512)
    private String verifiedNote;

    /** 最近一次 serve 自检的结果 JSON（边车 /healthz 对该槽位的实际报告） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "serve_check_json", length = 16_777_216)
    private String serveCheckJson;

    /**
     * 最近一次自检的结论状态（OK/WARN/FAIL）。
     *
     * <p>冗余存一份是<b>故意的</b>：列表页要显示"上次自检过了没有"，为此把每行的自检报告 JSON
     * 全解析一遍纯属浪费（同 {@code item_count} 的口径）。两个字段在同一次 save 里写，
     * 不会各写各的。</p>
     */
    @Column(name = "serve_check_status", length = 16)
    private String serveCheckStatus;

    @Column(name = "serve_checked_at")
    private Instant serveCheckedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(length = 512)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getServeSlot() { return serveSlot; }
    public void setServeSlot(String serveSlot) { this.serveSlot = serveSlot; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getFingerprintPath() { return fingerprintPath; }
    public void setFingerprintPath(String fingerprintPath) { this.fingerprintPath = fingerprintPath; }
    public Long getFingerprintBytes() { return fingerprintBytes; }
    public void setFingerprintBytes(Long fingerprintBytes) { this.fingerprintBytes = fingerprintBytes; }
    public String getFingerprintSha256() { return fingerprintSha256; }
    public void setFingerprintSha256(String fingerprintSha256) { this.fingerprintSha256 = fingerprintSha256; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getCalibrationJson() { return calibrationJson; }
    public void setCalibrationJson(String calibrationJson) { this.calibrationJson = calibrationJson; }
    /** getter 兜底：存量行 NULL 读成"未验证"，否则界面把没验过的当放行依据 */
    public boolean isVerified() { return Boolean.TRUE.equals(verified); }
    public void setVerified(Boolean verified) { this.verified = verified; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerifiedNote() { return verifiedNote; }
    public void setVerifiedNote(String verifiedNote) { this.verifiedNote = verifiedNote; }
    public String getServeCheckJson() { return serveCheckJson; }
    public void setServeCheckJson(String serveCheckJson) { this.serveCheckJson = serveCheckJson; }
    public String getServeCheckStatus() { return serveCheckStatus; }
    public void setServeCheckStatus(String serveCheckStatus) { this.serveCheckStatus = serveCheckStatus; }
    public Instant getServeCheckedAt() { return serveCheckedAt; }
    public void setServeCheckedAt(Instant serveCheckedAt) { this.serveCheckedAt = serveCheckedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
