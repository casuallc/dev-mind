package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * worklog_entries 表（CAP-89 FR-02 重建，字段沿用 CAP-28 FR-03）：个人工作条目，每天多条。
 * 工时内部以分钟存储（0.25h=15），API 暴露 hours。
 * 列名规避 H2 保留字：work_date 不叫 day、commit_sha 不叫 commit、jira_issue_key 不叫 key。
 *
 * <p><strong>主键 = String uuid（32hex，应用侧生成，无 @GeneratedValue）</strong>——平台惯例
 * 主键 Long，此处为保前端契约的有意例外：CAP-74 文件时代已做过一次 Long→String 破坏性变更，
 * 回 DB 不再二次翻转（CAP-89 §10 载明；勿"顺手"改回 Long）。</p>
 *
 * <p><strong>存量库注意</strong>：CAP-28 时代 id 为 bigint IDENTITY，ddl-auto=update 不改
 * 存量列类型——老库必须手工 ALTER id → varchar(32)（口径见 CAP-89 §5，194 于 2026-10-26
 * 已执行），否则 PG 读写炸 "operator does not exist: bigint = character varying"。</p>
 *
 * <p>unique(user_id, repo_id, commit_sha)：GIT 来源导入判重（CAP-28 原口径恢复；
 * 手动条目 repo_id/commit_sha 为 NULL，唯一约束天然不拦）。</p>
 */
@Entity
@Table(name = "worklog_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "repo_id", "commit_sha"}))
public class WorklogEntryEntity {

    public static final String TYPE_DEV = "DEV";
    public static final String TYPE_SUPPORT = "SUPPORT";
    public static final String TYPE_MEETING = "MEETING";
    public static final String TYPE_RESEARCH = "RESEARCH";
    public static final String TYPE_OTHER = "OTHER";

    public static final String SOURCE_GIT = "GIT";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 预留：agent 会话自动转条目 */
    public static final String SOURCE_AGENT = "AGENT";

    /** CAP-90 FR-01/02：条目状态——TODO/DOING=计划态（日计划并入条目），DONE=事实；
     *  DONE/DROPPED 人工，CARRIED 仅滚转写入；GIT/AGENT 来源恒 DONE。素材/工时一律 DONE-only。 */
    public static final String STATUS_TODO = "TODO";
    public static final String STATUS_DOING = "DOING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DROPPED = "DROPPED";
    public static final String STATUS_CARRIED = "CARRIED";

    /** uuid 32hex（WorklogEntryService 应用侧生成） */
    @Id
    @Column(length = 32)
    private String id;

    /** 归属用户（users.username，弱关联不设外键） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false, length = 256)
    private String title;

    /** 详情（@Lob 必带 @JdbcTypeCode + length：裸 @Lob 在 PG 落 oid / MySQL 落 tinytext） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = 16_777_216)
    private String content;

    @Column(name = "entry_type", nullable = false, length = 32)
    @ColumnDefault("'DEV'")
    private String entryType = TYPE_DEV;

    /** 工时（分钟） */
    @Column(nullable = false)
    private Integer minutes = 0;

    /** CAP-90 FR-01：TODO/DOING/DONE/DROPPED/CARRIED（@ColumnDefault 字符串必带引号；
     *  存量事实行由 DDL 默认值回填 DONE） */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'DONE'")
    private String status = STATUS_DONE;

    /** CAP-90 FR-01：计划态预估工时（分钟；可空=未预估），「完成」时预填确认 */
    @Column(name = "estimate_minutes")
    private Integer estimateMinutes;

    /** CAP-90 FR-05：日粒度滚转来源条目 uuid（自引用，拖延链可追溯） */
    @Column(name = "carried_from_id", length = 32)
    private String carriedFromId;

    /** 来源：GIT/MANUAL/AGENT */
    @Column(nullable = false, length = 16)
    private String source = SOURCE_MANUAL;

    /** GIT 来源时的仓库 */
    @Column(name = "repo_id")
    private Long repoId;

    /** GIT 来源去重键：(user_id, repo_id, commit_sha) 已存在则跳过 */
    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    /** 可选关联：研发主线需求 */
    @Column(name = "requirement_id", length = 64)
    private String requirementId;

    /** 可选关联：Jira issue key */
    @Column(name = "jira_issue_key", length = 64)
    private String jiraIssueKey;

    /** CAP-88 FR-04：可选关联计划项（worklog_plan_items.id；删计划项时置空） */
    @Column(name = "plan_item_id")
    private Long planItemId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getEstimateMinutes() { return estimateMinutes; }
    public void setEstimateMinutes(Integer estimateMinutes) { this.estimateMinutes = estimateMinutes; }
    public String getCarriedFromId() { return carriedFromId; }
    public void setCarriedFromId(String carriedFromId) { this.carriedFromId = carriedFromId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getJiraIssueKey() { return jiraIssueKey; }
    public void setJiraIssueKey(String jiraIssueKey) { this.jiraIssueKey = jiraIssueKey; }
    public Long getPlanItemId() { return planItemId; }
    public void setPlanItemId(Long planItemId) { this.planItemId = planItemId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
