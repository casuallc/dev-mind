package com.devmind.decision.record.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-55 FR-05 决策记录：一行 = 一次「模型建议 + 人工裁决」，攒成后续微调的 gold 数据集。
 *
 * <p><b>唯一键 (capability, subject_id)</b>：同一实体重新分诊/重新裁决时 upsert 覆盖同一行——
 * 训练样本要的是"最后一次建议 + 最后一次裁决"这一对；堆历史行只会让导出集里出现
 * 互相矛盾的样本（同一份 state 配两套 gold，模型学谁？）。降级样本也占一行：
 * "模型没给出建议"正是评估可用性的第一手数据。</p>
 *
 * <p><b>JSON 快照为什么存原文</b>：state/questions/model_answer 都要能<b>逐字</b>回放进训练集，
 * 任何"读库时重新拼装"都会随代码演进漂移，样本就不再是模型当初看到的那份。
 * 展示用视图（{@code DecisionRecordViews}）解析失败也不上抛——记录页坏一行不该 500。</p>
 */
@Entity
@Table(name = "decision_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_decision_records_capability_subject",
                columnNames = {"capability", "subject_id"}))
public class DecisionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 能力标识（如 kb-proposal-triage）：导出与筛选按它走，一个能力一份数据集 */
    @Column(length = 64, nullable = false)
    private String capability;

    /** 能力自己的实体 id（如提案 id）：样本要能追回原实体，人工复核时才说得清 */
    @Column(name = "subject_id", length = 64, nullable = false)
    private String subjectId;

    /** 实际发给模型的上下文快照（截断后的那份，逐字可复现） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String stateJson;

    /** 实际发出的题面（含 criteria——gold 的可选值由它决定，导出时靠它把答案摊成分布） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String questionsJson;

    /** 模型答案 JSON（题 id → type/choice/score/noul/confidence/probabilities） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String modelAnswer;

    /** 路由信息 JSON：{@code {"model":"multilingual","reason":"…"}}（边车实际选了谁、为什么） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String routingJson;

    /** 最近一次是降级（列 + getter 兜底：Boolean 列禁 @ColumnDefault，见红线） */
    private Boolean degraded = false;

    /** 降级原因（已脱敏，可直接展示） */
    @Column(length = 512)
    private String degradedReason;

    /** 模型那次往返耗时（毫秒）：降级率与延迟分布都靠它 */
    private Integer latencyMs;

    /** 人工裁决的 gold（题 id → 答案；空 = 这次裁决不构成任何题的 gold） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String goldJson;

    /** 人工动作机器值（adopt:global / adopt:project / reject）：gold 为空时也要留住"人做了什么" */
    @Column(length = 64)
    private String humanAction;

    @Column(length = 64)
    private String decidedBy;

    private Instant decidedAt;

    /** 模型建议产生的时间（= 分诊那一刻；与 decidedAt 一起算"人隔了多久才裁决"） */
    private Instant suggestedAt;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getCapability() { return capability; }

    public void setCapability(String capability) { this.capability = capability; }

    public String getSubjectId() { return subjectId; }

    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }

    public String getStateJson() { return stateJson; }

    public void setStateJson(String stateJson) { this.stateJson = stateJson; }

    public String getQuestionsJson() { return questionsJson; }

    public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }

    public String getModelAnswer() { return modelAnswer; }

    public void setModelAnswer(String modelAnswer) { this.modelAnswer = modelAnswer; }

    public String getRoutingJson() { return routingJson; }

    public void setRoutingJson(String routingJson) { this.routingJson = routingJson; }

    /** 兜底 false：存量行/未写的列是 NULL，直接拆箱会 NPE */
    public boolean isDegraded() { return degraded != null && degraded; }

    public void setDegraded(Boolean degraded) { this.degraded = degraded; }

    public String getDegradedReason() { return degradedReason; }

    public void setDegradedReason(String degradedReason) { this.degradedReason = degradedReason; }

    public Integer getLatencyMs() { return latencyMs; }

    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public String getGoldJson() { return goldJson; }

    public void setGoldJson(String goldJson) { this.goldJson = goldJson; }

    public String getHumanAction() { return humanAction; }

    public void setHumanAction(String humanAction) { this.humanAction = humanAction; }

    public String getDecidedBy() { return decidedBy; }

    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }

    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public Instant getSuggestedAt() { return suggestedAt; }

    public void setSuggestedAt(Instant suggestedAt) { this.suggestedAt = suggestedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
