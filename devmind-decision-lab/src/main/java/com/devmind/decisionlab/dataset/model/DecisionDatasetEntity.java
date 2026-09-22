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
 * CAP-56 FR-02 评测集（{@code decision_datasets}）。
 *
 * <p><b>冻结的意义是"这份评测集不再变"</b>：指标只有在输入固定时才可比，所以改内容不是 edit
 * 而是 {@code revise}——复制成一个新版本行（{@link #version} +1），旧版本原样留着，
 * 历史报告仍能指回它当时跑的是哪一份。冻结后条目只读，服务侧拒绝一切写操作。</p>
 *
 * <p><b>两个版本号是两回事，都要进指标主键</b>：
 * <ul>
 *   <li>{@link #version}——<b>评测集自己的修订号</b>（1,2,3…），冻结时定格；</li>
 *   <li>{@link #questionSetVersion}——所测的<b>题面版本</b>（{@code TriageQuestions.VERSION}
 *       {@code @1} 这类）。题面是代码常量，改一句文案就是换训练目标；不记它，历史指标会被
 *       无声地对齐到新题面上（数字看着没变，测的已不是一回事）。</li>
 * </ul>
 * 后者在<b>冻结时从条目里推</b>（条目各自带标注时的题面版本），不是建集时按当时的代码写死——
 * 否则「用旧题面标的 gold」会被盖上今天的版本号。</p>
 */
@Entity
@Table(name = "decision_datasets")
public class DecisionDatasetEntity {

    /** 人工标注的基准集（页面上一题一题标出来的，样本量小但可信） */
    public static final String KIND_BENCHMARK = "BENCHMARK";
    /** 从 decision_records 回流收编（CAP-55 数据飞轮的下半圈，量大但有偏） */
    public static final String KIND_REPLAY = "REPLAY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** BENCHMARK / REPLAY */
    @Column(length = 16)
    private String kind;

    /**
     * 评测集修订号。列名 {@code dataset_version}：{@code version} 是 H2 保留字，
     * 裸用会让建表直接失败（项目红线）。
     */
    @Column(name = "dataset_version", nullable = false)
    private int version = 1;

    /**
     * 冻结后只读。Boolean 列<b>禁 {@code @ColumnDefault}</b>（MySQL bit 列 default 'false' 建列失败）：
     * 走实体初始值 + getter 兜底，存量行的 NULL 由 {@code DecisionLabMigration} 补一次。
     */
    @Column(nullable = true)
    private Boolean frozen = Boolean.FALSE;

    /** 冻结时从条目推出的题面版本（草稿期为空——还没定下来，不假装有） */
    @Column(name = "question_set_version", length = 64)
    private String questionSetVersion;

    /** 条数缓存：列表页一页要显示几十个集的条数，逐行 count 是 N+1；条数只在条目增删时变 */
    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(length = 512)
    private String note;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "frozen_by", length = 64)
    private String frozenBy;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    /** 冻结时刻盖的旁证：冻的是什么内容（条数 + 题面版本），列表上一眼可读 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "freeze_manifest", length = 16_777_216)
    private String freezeManifest;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    /** getter 兜底：存量行 NULL 也要读成"未冻结"，否则界面把草稿当冻结、写操作被自己挡掉 */
    public boolean isFrozen() { return Boolean.TRUE.equals(frozen); }
    public void setFrozen(Boolean frozen) { this.frozen = frozen; }
    public String getQuestionSetVersion() { return questionSetVersion; }
    public void setQuestionSetVersion(String questionSetVersion) { this.questionSetVersion = questionSetVersion; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getFrozenBy() { return frozenBy; }
    public void setFrozenBy(String frozenBy) { this.frozenBy = frozenBy; }
    public Instant getFrozenAt() { return frozenAt; }
    public void setFrozenAt(Instant frozenAt) { this.frozenAt = frozenAt; }
    public String getFreezeManifest() { return freezeManifest; }
    public void setFreezeManifest(String freezeManifest) { this.freezeManifest = freezeManifest; }
}
