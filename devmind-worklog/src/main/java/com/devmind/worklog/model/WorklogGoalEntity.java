package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * worklog_goals 表（CAP-91 FR-01）：目标一等实体——年度目标 + 季度 OKR 同表
 * （scope=YEAR/QUARTER × kind=OBJECTIVE/KEY_RESULT；年/季同为低频实体，字段同构不拆表）。
 * period：YEAR='2026'；QUARTER='2026-Q1'（格式服务端校验，见 WorklogQuarters）。
 *
 * <p>目标体系（CAP-91 M4）：framework 标识目标采用的管理形式，当前仅 OKR（O→KR 层级
 * 规则/复盘投影/计划对齐均按 OKR 语义）；列与字段先行落地、非 OKR 一律 400，为后续
 * 其他形式（如纯目标清单、KPI）预留扩展点——层级校验以 framework 为分发缝，新体系
 * 在 WorklogGoalService 挂自己的规则集即可，不动表结构。</p>
 *
 * <p>层级（parent_id 自引用）：KR 必挂同 scope+period 的 O；QUARTER O 可选挂同年 YEAR O；
 * YEAR O 必空。</p>
 *
 * <p>状态机（FR-03）：ACTIVE/DONE/DROPPED/CARRIED 全人工（目标是低频实体不做自动流转）；
 * CARRIED 仅滚转写入，人工不可设、已滚转项只读（同 CAP-88 计划项口径）。progress 人工
 * 0-100；派生建议进度只在 tree 视图展示不回写（FR-07，「自动草稿+人工确认」同构）。</p>
 */
@Entity
@Table(name = "worklog_goals")
public class WorklogGoalEntity {

    public static final String SCOPE_YEAR = "YEAR";
    public static final String SCOPE_QUARTER = "QUARTER";

    public static final String KIND_OBJECTIVE = "OBJECTIVE";
    public static final String KIND_KEY_RESULT = "KEY_RESULT";

    /** 目标体系：当前唯一实现（O→KR 语义）；新形式在此登记常量并挂自己的校验规则集 */
    public static final String FRAMEWORK_OKR = "OKR";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DROPPED = "DROPPED";
    public static final String STATUS_CARRIED = "CARRIED";

    /** 平台惯例 Long 自增（同计划项） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username，弱关联不设外键；按人隔离，他人 404） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** YEAR / QUARTER */
    @Column(nullable = false, length = 8)
    private String scope;

    /** YEAR='2026'；QUARTER='2026-Q1' */
    @Column(nullable = false, length = 8)
    private String period;

    /** OBJECTIVE / KEY_RESULT */
    @Column(nullable = false, length = 16)
    private String kind;

    /** 目标体系（CAP-91 M4）：OKR=目标+关键结果；身份属性不可改。后续非 OKR 形式的扩展点 */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'OKR'")
    private String framework = FRAMEWORK_OKR;

    /** 父目标 id：KR→同周期 O（必填）；QUARTER O→同年 YEAR O（可选）；YEAR O 必空 */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 256)
    private String title;

    /** 详情（@Lob 必带 @JdbcTypeCode + length：裸 @Lob 在 PG 落 oid / MySQL 落 tinytext） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String content;

    /** 权重%（可空；首版只存，O 进度自动汇总见 CAP-91 §10 O2） */
    private Integer weight;

    /** 人工进度 0-100（派生建议值只展示不回写，FR-07） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer progress = 0;

    /** ACTIVE/DONE/DROPPED/CARRIED（@ColumnDefault 字符串必带引号） */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'ACTIVE'")
    private String status = STATUS_ACTIVE;

    /** 跨周期滚转来源目标 id（自引用，「一个 KR 拖了三季」可回溯） */
    @Column(name = "carried_from_id")
    private Long carriedFromId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCarriedFromId() { return carriedFromId; }
    public void setCarriedFromId(Long carriedFromId) { this.carriedFromId = carriedFromId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
