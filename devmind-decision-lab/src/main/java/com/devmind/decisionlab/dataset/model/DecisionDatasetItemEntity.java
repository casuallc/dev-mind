package com.devmind.decisionlab.dataset.model;

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
 * CAP-56 FR-02 评测样本（{@code decision_dataset_items}）：一条「输入 + 题面 + 标准答案」。
 *
 * <p><b>三份 JSON 与 decision_records 同形状，这是刻意的</b>：{@code state}/{@code questions}/
 * {@code gold} 正是 CAP-55 训练集导出的三个字段，gold 也是<b>人工原值</b>（不是摊好的分布）——
 * 分布随时可用 {@code GoldDistributions} 从原值 + 题面现算。共用一套形状的好处是
 * 「从决策记录收编」就是把原记录三份 JSON 搬过来（FR-02 回流集），不需要做任何转换，
 * 也就没有转换出错的机会。</p>
 *
 * <p><b>题面随条目快照，不随代码走</b>：{@link #questionsJson} 是标注时那套题面的原文，
 * {@link #questionSetVersion} 记它是什么版本。这样即便代码里的题面改了，这条样本仍能被
 * 原样重放（指标比的是"当时那道题"），也才敢把题面版本放进指标主键。</p>
 *
 * <p><b>{@link #caseGroup} 是评测集合法性的支点</b>：FR-02 的对照组红线不能只靠"打了个标签"，
 * 冻结时会真去查 state 长什么样（见 {@code CaseGroups.holds}）——标签与内容对不上就拒绝冻结。</p>
 */
@Entity
@Table(name = "decision_dataset_items")
public class DecisionDatasetItemEntity {

    /** 页面人工标注 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 从 decision_records 回流收编 */
    public static final String SOURCE_RECORD = "RECORD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    /** 喂模型的 state 快照（JSON 对象） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "state_json", length = 16_777_216)
    private String stateJson;

    /** 标注时那套题面的原文（JSON 对象，{题 id: {type,instructions,criteria}}） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "questions_json", length = 16_777_216)
    private String questionsJson;

    /** 人工答案原文（JSON 对象，{题 id: 原值}）；分布由 GoldDistributions 现算 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "gold_json", length = 16_777_216)
    private String goldJson;

    /** MANUAL / RECORD */
    @Column(length = 16)
    private String source;

    /** 收编来源的 decision_records.id（MANUAL 为空，用于回溯"这条是谁裁的"） */
    @Column(name = "origin_record_id")
    private Long originRecordId;

    /** NORMAL / EMPTY_RECALL / VERBATIM_DUP / IRRELEVANT（见 CaseGroups） */
    @Column(name = "case_group", length = 24)
    private String caseGroup;

    /** 标注时的题面版本（冻结时各条目必须一致，否则整集的指标没有共同分母） */
    @Column(name = "question_set_version", length = 64)
    private String questionSetVersion;

    @Column(length = 512)
    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public String getQuestionsJson() { return questionsJson; }
    public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }
    public String getGoldJson() { return goldJson; }
    public void setGoldJson(String goldJson) { this.goldJson = goldJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getOriginRecordId() { return originRecordId; }
    public void setOriginRecordId(Long originRecordId) { this.originRecordId = originRecordId; }
    public String getCaseGroup() { return caseGroup; }
    public void setCaseGroup(String caseGroup) { this.caseGroup = caseGroup; }
    public String getQuestionSetVersion() { return questionSetVersion; }
    public void setQuestionSetVersion(String questionSetVersion) { this.questionSetVersion = questionSetVersion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
