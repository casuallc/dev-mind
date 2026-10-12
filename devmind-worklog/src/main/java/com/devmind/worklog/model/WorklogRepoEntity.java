package com.devmind.worklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * worklog_repos 表（CAP-74 FR-01）：工作日志仓——服务端本地 git 仓为事实源，
 * 可选绑定远端备份。个人仓 = 成员为 1 的仓（repo_key = personal-&lt;username&gt;）。
 * 本机路径不入库：物理路径 = 配置 devmind.worklog.repo-root + repo_key 派生。
 */
@Entity
@Table(name = "worklog_repos")
public class WorklogRepoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 仓键（个人仓 personal-<sanitized-username>；团队仓由仓主命名），全局唯一 */
    @Column(name = "repo_key", nullable = false, length = 64, unique = true)
    private String repoKey;

    /** 远端备份仓库 URL（http/https/file，不含凭证；null = 纯本地仓） */
    @Column(name = "remote_url", length = 512)
    private String remoteUrl;

    /** 远端目标分支（本地恒 main，push 走 refspec；存量行平滑加列靠默认值+初始值） */
    @Column(name = "remote_branch", nullable = false, length = 128)
    @ColumnDefault("'main'")
    private String remoteBranch = "main";

    /** 仓主（创建者 username；团队仓的成员管理/绑定远端由仓主操作） */
    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRepoKey() { return repoKey; }
    public void setRepoKey(String repoKey) { this.repoKey = repoKey; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String remoteUrl) { this.remoteUrl = remoteUrl; }
    public String getRemoteBranch() { return remoteBranch; }
    public void setRemoteBranch(String remoteBranch) { this.remoteBranch = remoteBranch; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
