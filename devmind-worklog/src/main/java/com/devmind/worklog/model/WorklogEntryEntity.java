package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

/**
 * worklog_entries 表（CAP-28 FR-03）：个人工作条目，每天多条，各记工时。
 * 工时内部以分钟存储（0.25h=15），避免浮点；API 暴露 hours。
 * 列名规避 H2 保留字：work_date 不叫 day、commit_sha 不叫 commit、jira_issue_key 不叫 key。
 */
@Entity
@Table(name = "worklog_entries")
public class WorklogEntryEntity {

    public static final String TYPE_DEV = "DEV";
    public static final String TYPE_SUPPORT = "SUPPORT";
    public static final String TYPE_MEETING = "MEETING";
    public static final String TYPE_RESEARCH = "RESEARCH";
    public static final String TYPE_OTHER = "OTHER";

    public static final String SOURCE_GIT = "GIT";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 预留：agent 会话自动转条目（后续接 session.completed 事件） */
    public static final String SOURCE_AGENT = "AGENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username，弱关联不设外键） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false, length = 256)
    private String title;

    /** 详情（@Lob 必带 length：裸 @Lob 在 MySQL 落成 tinytext 的历史教训） */
    @Lob
    @Column(length = 16_777_216)
    private String content;

    @Column(name = "entry_type", nullable = false, length = 32)
    @ColumnDefault("'DEV'")
    private String entryType = TYPE_DEV;

    /** 工时（分钟） */
    @Column(nullable = false)
    private Integer minutes = 0;

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

    /** 可选关联：Jira issue key（FR-07 一键操作的落点） */
    @Column(name = "jira_issue_key", length = 64)
    private String jiraIssueKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
